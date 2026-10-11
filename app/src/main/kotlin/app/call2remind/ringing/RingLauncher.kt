package app.call2remind.ringing

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import app.call2remind.R
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.ringing.RingMode
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.scheduling.AlarmScheduler
import app.call2remind.scheduling.MissedNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Starts the ringing UI for an occurrence whose ring lock is already held. */
interface RingLauncher {
    companion object {
        /** FAILED reason: the ringing foreground service could not be started. */
        const val REASON_FGS_REFUSED = "fgs_start_not_allowed"

        /** FAILED reason: the fallback ring notification could not be posted (POST_NOTIFICATIONS denied or rejected). */
        const val REASON_NOTIFICATION_BLOCKED = "notification_blocked"
    }

    /** [occurrence] is RINGING: start the ringing service (or the fallback notification). */
    suspend fun startRinging(occurrence: Occurrence)

    /** [due] are due while the user is in a call: silent heads-up and ring when the call ends. */
    suspend fun deferUntilCallEnds(due: List<Occurrence>)
}

@Singleton
class AndroidRingLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminders: ReminderRepository,
    private val occurrences: OccurrenceRepository,
    private val notifications: RingNotifications,
    private val alarms: AlarmScheduler,
    private val clock: Clock,
) : RingLauncher {

    /**
     * Without a service there is no ring timer; the occurrence's deadline alarm (armed when the
     * ring was claimed, see [app.call2remind.scheduling.SchedulingEngine.claimNext]) times the
     * ring out and frees the line.
     */
    override suspend fun startRinging(occurrence: Occurrence) {
        val title = titleOf(occurrence)
        try {
            ContextCompat.startForegroundService(context, RingingService.ringIntent(context, occurrence.id, title))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) or background-start refusal:
            // ring with a high-priority, insistent CallStyle notification on the sounding channel.
            Log.w(TAG, "Cannot start ringing service", e)
            notifications.ensureChannels()
            val posted = notifications.notify(
                notifications.fallbackId(occurrence),
                notifications.incomingCall(occurrence.id, title, RingMode.FULL_SCREEN, fallback = true),
                RingNotifications.TAG_FALLBACK,
            )
            val now = clock.instant()
            occurrences.appendLog(RingLogEvent(occurrence.id, RingLogType.FAILED, now, RingLauncher.REASON_FGS_REFUSED))
            if (!posted) {
                Log.e(TAG, "Fallback ring notification not posted (notifications blocked?)")
                occurrences.appendLog(RingLogEvent(occurrence.id, RingLogType.FAILED, now, RingLauncher.REASON_NOTIFICATION_BLOCKED))
            }
        }
    }

    override suspend fun deferUntilCallEnds(due: List<Occurrence>) {
        if (due.isEmpty()) return
        val titles = due.map { titleOf(it) }
        try {
            ContextCompat.startForegroundService(context, RingingService.deferIntent(context, titles))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start ringing service to defer", e)
            notifications.ensureChannels()
            notifications.notify(RingNotifications.RING_NOTIFICATION_ID, notifications.deferred(titles))
            // No service to watch the call: re-check with a short alarm instead.
            alarms.arm(due.first(), clock.instant().plus(DEFER_RECHECK), isSoonest = true)
        }
    }

    private suspend fun titleOf(occurrence: Occurrence): String =
        reminders.get(occurrence.reminderId)?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.reminder_fallback_title)

    private companion object {
        const val TAG = "RingLauncher"
        val DEFER_RECHECK: Duration = Duration.ofMinutes(1)
    }
}

/** [MissedNotifier] posting "Missed reminder" notifications. */
@Singleton
class AndroidMissedNotifier @Inject constructor(
    private val reminders: ReminderRepository,
    private val notifications: RingNotifications,
) : MissedNotifier {
    override suspend fun onMissed(occurrence: Occurrence) {
        val title = reminders.get(occurrence.reminderId)?.title.orEmpty()
        notifications.notify(
            notifications.missedId(occurrence),
            notifications.missed(occurrence, title),
            RingNotifications.TAG_MISSED,
        )
    }

    override suspend fun onResolved(occurrence: Occurrence) {
        notifications.cancel(notifications.missedId(occurrence), RingNotifications.TAG_MISSED)
    }

    override suspend fun onRingEnded(occurrence: Occurrence) {
        notifications.cancel(notifications.fallbackId(occurrence), RingNotifications.TAG_FALLBACK)
    }
}
