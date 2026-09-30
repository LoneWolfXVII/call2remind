package app.call2remind.ringing

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import app.call2remind.R
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.ringing.RecoveryPolicy
import app.call2remind.core.ringing.RingMode
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.scheduling.AlarmScheduler
import app.call2remind.scheduling.MissedNotifier
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Starts the ringing UI for an occurrence whose ring lock is already held. */
interface RingLauncher {
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
    private val settings: SettingsRepository,
    private val clock: Clock,
) : RingLauncher {

    override suspend fun startRinging(occurrence: Occurrence) {
        val title = titleOf(occurrence)
        try {
            ContextCompat.startForegroundService(context, RingingService.ringIntent(context, occurrence.id, title))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) or background-start refusal:
            // ring with a high-priority CallStyle notification on the sounding channel instead.
            Log.w(TAG, "Cannot start ringing service", e)
            notifications.ensureChannels()
            notifications.notify(
                notifications.fallbackId(occurrence),
                notifications.incomingCall(occurrence.id, title, RingMode.FULL_SCREEN, fallback = true),
                RingNotifications.TAG_FALLBACK,
            )
            val now = clock.instant()
            occurrences.appendLog(RingLogEvent(occurrence.id, RingLogType.FAILED, now, REASON_FGS_REFUSED))
            // No service means no ring timer: an alarm just after the recovery threshold lets the
            // alarm receiver's recovery time the ring out (auto-snooze) and free the line.
            val timeoutAt = now
                .plus(settings.current().snoozePolicy.ringTimeout)
                .plus(RecoveryPolicy().ringingGrace)
                .plus(TIMEOUT_SLACK)
            alarms.arm(occurrence, timeoutAt, isSoonest = false)
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
        const val REASON_FGS_REFUSED = "fgs_start_not_allowed"
        val DEFER_RECHECK: Duration = Duration.ofMinutes(1)
        val TIMEOUT_SLACK: Duration = Duration.ofSeconds(1)
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
