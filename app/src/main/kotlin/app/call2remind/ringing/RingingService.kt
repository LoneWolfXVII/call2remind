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
 *    policy's ring timeout.
 * 2. It observes the occurrence in Room: once answered it stops the alerts, switches to an
 *    ongoing-call notification and speaks the reminder (TTS); once it leaves RINGING (done,
 *    snoozed, missed…) it ends the ring and runs the ring queue ([SchedulingEngine.claimNext]) —
 *    the next due occurrence rings in the same service, or it stops.
 * 3. `ACTION_DEFER` (user in a real call): silent heads-up, waits for the call to end, then runs
 *    the ring queue.
 *
 * Every state change goes through [SchedulingEngine.handle] (from the call screen, notification
 * actions or the timeout), so this service only reacts to the database.
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

    private var ringingId: String? = null
    private var ringJob: Job? = null
    private var deferJob: Job? = null
    private var lastStartId = 0
    private var lastNotification: Notification? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        notifications.ensureChannels()
        when (intent?.action) {
            ACTION_RING -> {
                val id = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.reminder_fallback_title)
                if (id == null) {
                    promote(lastNotification ?: notifications.deferred(emptyList()))
                    lifecycleScope.launch { pump() }
                } else {
                    val mode = RingDecision.decide(ringContextProvider.current())
                    promote(notifications.incomingCall(id, title, mode))
                    if (id != ringingId) startRing(id, title)
                }
            }
            ACTION_DEFER -> {
                if (ringingId == null) {
                    promote(notifications.deferred(intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()))
                    startDeferWatch()
                } else {
                    promote(lastNotification ?: notifications.deferred(emptyList()))
                }
            }
            else -> {
                promote(lastNotification ?: notifications.deferred(emptyList()))
                if (ringingId == null) lifecycleScope.launch { pump() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alerts.stop()
        tts.stop()
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
        val occurrence = occurrences.get(id)
        if (occurrence == null || occurrence.state != OccurrenceState.RINGING) {
            onRingFinished(id)
            return
        }
        val reminder = reminders.get(occurrence.reminderId)
        val settings = settingsRepository.current()
        val ringContext = ringContextProvider.current()
        val mode = RingDecision.decide(ringContext)
        val title = reminder?.title?.takeIf { it.isNotBlank() } ?: initialTitle
        promote(notifications.incomingCall(id, title, mode))
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
            var answered = false
            occurrences.observe(id)
                .takeWhile { it?.state == OccurrenceState.RINGING }
                .collect { current ->
                    if (current != null && current.answeredAt != null && !answered) {
                        answered = true
                        timeout.cancel()
                        alerts.stop()
                        promote(notifications.ongoingCall(id, title))
                        if (reminder != null && reminder.ttsEnabled && settings.ttsEnabled) {
                            launch { tts.speak(SpeechText.build(reminder, current, ZoneId.systemDefault())) }
                        }
                    }
                }
            timeout.cancel()
            coroutineContext.cancelChildren()
        }
        alerts.stop()
        tts.stop()
        onRingFinished(id)
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
        val step = engine.claimNext()
        engine.reconcile()
        when (step) {
            is RingStep.Ring -> startRing(step.occurrence.id, titleOf(step.occurrence))
            is RingStep.Defer -> {
                promote(notifications.deferred(step.due.map { titleOf(it) }))
                startDeferWatch()
            }
            RingStep.LineBusy, RingStep.Idle -> stopWhenIdle()
        }
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
        notifications.cancel(RingNotifications.RING_NOTIFICATION_ID)
        lastNotification = null
        stopSelfResult(lastStartId)
    }

    private fun promote(notification: Notification) {
        lastNotification = notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    RingNotifications.RING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(RingNotifications.RING_NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: keep going as a plain notification.
            Log.w(TAG, "startForeground refused", e)
            notifications.notify(RingNotifications.RING_NOTIFICATION_ID, notification)
        }
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
