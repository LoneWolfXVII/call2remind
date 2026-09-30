package app.call2remind.ringing

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.call2remind.R
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.RingDecision
import app.call2remind.core.ringing.RingMode
import app.call2remind.core.speech.SpeechText
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.scheduling.RingStep
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.Settings
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

/**
 * Foreground service (`specialUse`, subtype "reminder ringing") that presents one ringing
 * occurrence at a time:
 *
 * 1. `ACTION_RING` (ring lock already held): CallStyle notification + full-screen intent per
 *    [RingDecision], looping ringtone / vibration, auto [OccurrenceEvent.RingTimeout] after the
 *    policy's ring timeout. Each occurrence rings under its own notification id
 *    ([RingNotifications.ringId]), so a queued call alerts (heads-up / full-screen) afresh.
 * 2. It observes the occurrence in Room: once answered it stops the alerts, switches to an
 *    ongoing-call notification and speaks the reminder (TTS); once it leaves RINGING (done,
 *    snoozed, missed…) it ends the ring and runs the ring queue ([SchedulingEngine.claimNext]) —
 *    the next due occurrence rings in the same service, or it stops.
 * 3. A real phone call — already active when the ring starts ([RingMode.DEFER_UNTIL_CALL_ENDS])
 *    or starting before it is answered — silences the ring and gives the line back
 *    ([OccurrenceEvent.Defer], no ring-back used); the queue then defers: silent heads-up until
 *    the call ends ([CallStateMonitor]), then it rings. `ACTION_DEFER` enters that wait directly.
 *
 * Every state change goes through [SchedulingEngine.handle] (from the call screen, notification
 * actions, the timeout or a call starting), so this service only reacts to the database.
 */
@AndroidEntryPoint
class RingingService : LifecycleService() {
    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var occurrences: OccurrenceRepository

    @Inject lateinit var reminders: ReminderRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var ringContextProvider: RingContextProvider

    @Inject lateinit var callStateMonitor: CallStateMonitor

    @Inject lateinit var notifications: RingNotifications

    @Inject lateinit var alerts: RingAlerts

    @Inject lateinit var tts: TtsPlayer

    @Inject lateinit var wakeLock: RingWakeLock

    /** A notification the service shows, and how to show it if it cannot be the foreground one. */
    private class Shown(val id: Int, val notification: Notification, val outsideForeground: () -> Notification)

    private var ringingId: String? = null
    private var ringJob: Job? = null
    private var deferJob: Job? = null
    private var lastStartId = 0
    private var shown: Shown? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        notifications.ensureChannels()
        when (intent?.action) {
            ACTION_RING -> {
                val id = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.reminder_fallback_title)
                when {
                    id == null -> {
                        promoteLast()
                        lifecycleScope.launch { pump() }
                    }
                    id == ringingId -> promoteLast()
                    else -> {
                        val mode = RingDecision.decide(ringContextProvider.current())
                        if (mode == RingMode.DEFER_UNTIL_CALL_ENDS) {
                            promoteDeferred(listOf(title))
                        } else {
                            promoteIncoming(id, title, mode)
                        }
                        startRing(id, title)
                    }
                }
            }
            ACTION_DEFER -> {
                if (ringingId == null) {
                    promoteDeferred(intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty())
                    startDeferWatch()
                } else {
                    promoteLast()
                }
            }
            else -> {
                promoteLast()
                if (ringingId == null) lifecycleScope.launch { pump() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alerts.stop()
        tts.stop()
        wakeLock.release()
        super.onDestroy()
    }

    private fun startRing(id: String, title: String) {
        deferJob?.cancel()
        deferJob = null
        ringJob?.cancel()
        ringingId = id
        ringJob = lifecycleScope.launch { runRing(id, title) }
    }

    private suspend fun runRing(id: String, initialTitle: String) {
        try {
            val occurrence = occurrences.get(id)
            if (occurrence == null || occurrence.state != OccurrenceState.RINGING) return
            val reminder = reminders.get(occurrence.reminderId)
            val settings = settingsRepository.current()
            wakeLock.acquire(settings.snoozePolicy.ringTimeout.toMillis() + RingWakeLock.RING_MARGIN_MS)
            val ringContext = ringContextProvider.current()
            val mode = RingDecision.decide(ringContext)
            if (mode == RingMode.DEFER_UNTIL_CALL_ENDS) {
                // A real call started between the claim and now: give the line back (no ring-back
                // used); the queue then defers until the call ends.
                withContext(NonCancellable) { engine.handle(id, OccurrenceEvent.Defer) }
                return
            }
            val title = reminder?.title?.takeIf { it.isNotBlank() } ?: initialTitle
            promoteIncoming(id, title, mode)
            if (mode == RingMode.IN_APP_OVERLAY) showCallScreen(id)
            if (mode == RingMode.SILENT_FULL_SCREEN_VIBRATE) maybeShowDndHint(settings)
            alerts.start(
                ringtoneUri = reminder?.let(settings::ringtoneFor) ?: settings.defaultRingtoneUri,
                sound = RingDecision.soundAllowed(ringContext),
                vibrate = RingDecision.vibrationAllowed(ringContext),
            )

            coroutineScope {
                val timeout = launch {
                    delay(settings.snoozePolicy.ringTimeout.toMillis())
                    withContext(NonCancellable) { engine.handle(id, OccurrenceEvent.RingTimeout) }
                }
                launch { watchForRealCall(id) }
                var answered = false
                occurrences.observe(id)
                    .takeWhile { it?.state == OccurrenceState.RINGING }
                    .collect { current ->
                        if (current != null && current.answeredAt != null && !answered) {
                            answered = true
                            timeout.cancel()
                            alerts.stop()
                            promote(
                                Shown(notifications.ringId(id), notifications.ongoingCall(id, title)) {
                                    notifications.ongoingCall(id, title, callStyle = false)
                                },
                            )
                            if (reminder != null && reminder.ttsEnabled && settings.ttsEnabled) {
                                launch { tts.speak(SpeechText.build(reminder, current, ZoneId.systemDefault())) }
                            }
                        }
                    }
                timeout.cancel()
                coroutineContext.cancelChildren()
            }
        } finally {
            // A ring replaced by another (startRing) must not silence its successor.
            if (ringingId == id) {
                alerts.stop()
                tts.stop()
                wakeLock.release()
            }
            onRingFinished(id)
        }
    }

    /**
     * A real call starting mid-ring: an unanswered ring goes silent and gives the line back
     * ([OccurrenceEvent.Defer]; it rings again when the call ends); an answered one stops talking.
     */
    private suspend fun watchForRealCall(id: String) {
        callStateMonitor.awaitCallStarted()
        val current = occurrences.get(id)
        if (current == null || current.state != OccurrenceState.RINGING) return
        if (current.answeredAt == null) {
            alerts.stop()
            withContext(NonCancellable) { engine.handle(id, OccurrenceEvent.Defer) }
        } else {
            tts.stop()
        }
    }

    private fun onRingFinished(id: String) {
        if (ringingId == id) {
            ringingId = null
            ringJob = null
        }
        lifecycleScope.launch { pump() }
    }

    /** Runs the ring queue: ring the next due occurrence here, defer, or stop. */
    private suspend fun pump() {
        if (ringingId != null) return
        when (val step = engine.claimNext()) {
            is RingStep.Ring -> startRing(step.occurrence.id, titleOf(step.occurrence))
            is RingStep.Defer -> {
                promoteDeferred(step.due.map { titleOf(it) })
                startDeferWatch()
            }
            RingStep.LineBusy, RingStep.Idle -> stopWhenIdle()
        }
        engine.reconcile()
    }

    private fun startDeferWatch() {
        if (deferJob?.isActive == true) return
        deferJob = lifecycleScope.launch {
            callStateMonitor.awaitCallEnded()
            deferJob = null
            pump()
        }
    }

    private fun stopWhenIdle() {
        if (ringingId != null || deferJob?.isActive == true) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        shown?.let { notifications.cancel(it.id) }
        notifications.cancel(RingNotifications.RING_NOTIFICATION_ID)
        shown = null
        stopSelfResult(lastStartId)
    }

    private fun promoteIncoming(id: String, title: String, mode: RingMode) {
        promote(
            Shown(notifications.ringId(id), notifications.incomingCall(id, title, mode)) {
                // Outside a foreground service a CallStyle notification needs a full-screen intent.
                notifications.incomingCall(id, title, mode, requireFullScreen = true)
            },
        )
    }

    private fun promoteDeferred(titles: List<String>) {
        val notification = notifications.deferred(titles)
        promote(Shown(RingNotifications.RING_NOTIFICATION_ID, notification) { notification })
    }

    /** Re-promotes what is shown (a start command must always reach `startForeground`). */
    private fun promoteLast() {
        val last = shown
        if (last != null) promote(last) else promoteDeferred(emptyList())
    }

    /**
     * Makes [next] the foreground notification. A different id than before (the next queued
     * call) is a new notification that alerts afresh; the previous one is removed.
     */
    private fun promote(next: Shown) {
        val previous = shown
        shown = next
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, next.id, next.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(next.id, next.notification)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: keep going as a plain notification, built
            // to be valid outside a foreground service (notify() never throws).
            Log.w(TAG, "startForeground refused", e)
            notifications.notify(next.id, next.outsideForeground())
        }
        if (previous != null && previous.id != next.id) notifications.cancel(previous.id)
    }

    private fun showCallScreen(id: String) {
        startActivity(IncomingCallActivity.intent(this, id, answer = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun maybeShowDndHint(settings: Settings) {
        if (settings.onboarding.dndHintShown) return
        notifications.notify(RingNotifications.DND_HINT_ID, notifications.dndHint())
        settingsRepository.update { it.copy(onboarding = it.onboarding.copy(dndHintShown = true)) }
    }

    private suspend fun titleOf(occurrence: Occurrence): String =
        reminders.get(occurrence.reminderId)?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.reminder_fallback_title)

    companion object {
        private const val TAG = "RingingService"
        const val ACTION_RING = "app.call2remind.action.RING"
        const val ACTION_DEFER = "app.call2remind.action.DEFER"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TITLES = "titles"

        /** Starts ringing [occurrenceId] (whose ring lock the caller holds). */
        fun ringIntent(context: Context, occurrenceId: String, title: String): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                .putExtra(EXTRA_TITLE, title)

        /** Silent heads-up for [titles] until the current phone call ends. */
        fun deferIntent(context: Context, titles: List<String>): Intent =
            Intent(context, RingingService::class.java)
                .setAction(ACTION_DEFER)
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
    }
}
