package app.call2remind.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.os.UserManagerCompat
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.time.DeviceClock
import app.call2remind.core.time.DeviceZonePolicy
import app.call2remind.di.ApplicationScope
import app.call2remind.scheduling.ReplanReason
import app.call2remind.scheduling.RingStep
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.ringing.RingLauncher
import app.call2remind.ringing.RingWakeLock
import app.call2remind.sync.SyncScheduler
import app.call2remind.work.BackgroundJobs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.TimeZone
import javax.inject.Inject

private const val TAG = "Receivers"

/**
 * Budget for work done after `goAsync()`: the system ANRs a broadcast after ~10 s. All work here
 * is idempotent and re-run by the watchdog, so a timeout only delays, never loses, a ring.
 * Work that must not be split (claiming a ring and starting it) runs `NonCancellable` and may
 * overrun the budget rather than orphan a claimed ring.
 */
internal const val RECEIVER_BUDGET_MS: Long = 9_000L

/**
 * Runs [block] on [scope] while keeping the broadcast alive (goAsync), bounded by
 * [RECEIVER_BUDGET_MS] and logging failures. `goAsync()` returns null when `onReceive` is
 * invoked directly (tests), hence the nullable result.
 */
internal fun BroadcastReceiver.runAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    scope.launch {
        try {
            val completed = withTimeoutOrNull(RECEIVER_BUDGET_MS) { block() }
            if (completed == null) Log.w(TAG, "Broadcast work exceeded ${RECEIVER_BUDGET_MS}ms and was cancelled")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast work failed", e)
        } finally {
            pending?.finish()
        }
    }
}

/**
 * An occurrence alarm fired (its ring time, or a ringing occurrence's deadline): take the ring
 * lock for the next due occurrence (ring queue) and start ringing — exact alarms allow starting
 * the foreground service from here, for a short window, so that happens before re-arming.
 *
 * Claim and service start run `NonCancellable`: the receiver budget must never cancel between
 * the claim's commit and the start (an orphaned RINGING row would block the line until its
 * deadline). A partial wake lock bridges the broadcast's end and the service's first ring.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var launcher: RingLauncher

    @Inject lateinit var wakeLock: RingWakeLock

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        runAsync(scope) {
            withContext(NonCancellable) {
                when (val step = engine.claimNext()) {
                    is RingStep.Ring -> {
                        wakeLock.acquire(RingWakeLock.RECEIVER_TIMEOUT_MS)
                        launcher.startRinging(step.occurrence)
                    }
                    is RingStep.Defer -> launcher.deferUntilCallEnds(step.due)
                    RingStep.LineBusy, RingStep.Idle -> Unit
                }
            }
            engine.reconcile()
        }
    }

    companion object {
        const val ACTION_FIRE = "app.call2remind.action.ALARM_FIRE"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    }
}

/**
 * BOOT_COMPLETED / LOCKED_BOOT_COMPLETED (direct boot) / MY_PACKAGE_REPLACED: alarms were lost,
 * so replan with a 2 h lookback, run recovery (ring recent misses, mark older ones missed) and
 * re-arm. WorkManager is credential-protected, so the watchdog is only scheduled once unlocked.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var jobs: BackgroundJobs

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> ReplanReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReplanReason.PACKAGE_REPLACED
            else -> return
        }
        runAsync(scope) {
            engine.recoverAfterBoot(reason)
            if (UserManagerCompat.isUserUnlocked(context)) jobs.ensureScheduled()
        }
    }
}

/**
 * Wall clock or time zone changed: wall-clock schedules resolve to new instants, and rings the
 * jump made overdue are recovered. Exact-alarm access granted (API 31+): re-arm so alarms that
 * fell back to inexact become exact again.
 *
 * Time zone change: synced date-only items (all-day events, task due dates, birthdays) and habits
 * are stored with a zone; left alone they would keep ringing at the old zone's wall-clock time
 * (09:00 in Kolkata = 20:30 the evening before in Los Angeles). The replan in
 * [SchedulingEngine.onTimeChanged] moves them to the new zone right here ([DeviceZonePolicy]),
 * without waiting for a sync. A forced re-sync then refreshes the sources in the new zone; it
 * REPLACEs a queued or running sync, which may have read the old zone.
 */
@AndroidEntryPoint
class TimeChangeReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED -> runAsync(scope) { engine.onTimeChanged() }
            Intent.ACTION_TIMEZONE_CHANGED -> {
                refreshDefaultZone(intent.getStringExtra(EXTRA_TIME_ZONE))
                runAsync(scope) {
                    engine.onTimeChanged()
                    // WorkManager lives in credential-protected storage: only once unlocked (a
                    // sync also runs on the first app open after unlock).
                    if (UserManagerCompat.isUserUnlocked(context)) syncScheduler.requestSync(replacePending = true)
                }
            }
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> runAsync(scope) { engine.reconcile() }
        }
    }

    companion object {
        /** `Intent.EXTRA_TIMEZONE` (API 30+): the new zone id on ACTION_TIMEZONE_CHANGED. */
        const val EXTRA_TIME_ZONE = "time-zone"

        /**
         * The system resets every process's default zone when the zone changes, but that call and
         * this broadcast are separate one-way IPCs. If the broadcast names a zone the process does
         * not report yet, drop the cached default so `ZoneId.systemDefault()` (and [DeviceClock])
         * re-read it from the system now.
         */
        internal fun refreshDefaultZone(newZoneId: String?) {
            if (newZoneId.isNullOrEmpty() || TimeZone.getDefault().id == newZoneId) return
            TimeZone.setDefault(null)
        }

        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31+). */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

/**
 * Notification actions: Answer / Decline / Snooze (optional [EXTRA_SNOOZE_MINUTES], else the
 * configured snooze length) / Done / Skip, each applied through [SchedulingEngine.handle].
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var settings: SettingsRepository

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
        val action = intent.action ?: return
        runAsync(scope) {
            val event = when (action) {
                ACTION_ANSWER -> OccurrenceEvent.Answer
                ACTION_DECLINE -> OccurrenceEvent.Decline
                ACTION_DONE -> OccurrenceEvent.Done
                ACTION_SKIP -> OccurrenceEvent.Skip
                ACTION_SNOOZE -> {
                    val minutes = intent.getLongExtra(EXTRA_SNOOZE_MINUTES, 0L)
                    val length = if (minutes > 0) Duration.ofMinutes(minutes) else settings.current().snoozePolicy.defaultSnooze
                    OccurrenceEvent.Snooze(length)
                }
                else -> null
            }
            if (event != null) engine.handle(id, event)
        }
    }

    companion object {
        const val ACTION_ANSWER = "app.call2remind.action.CALL_ANSWER"
        const val ACTION_DECLINE = "app.call2remind.action.CALL_DECLINE"
        const val ACTION_SNOOZE = "app.call2remind.action.CALL_SNOOZE"
        const val ACTION_DONE = "app.call2remind.action.CALL_DONE"
        const val ACTION_SKIP = "app.call2remind.action.CALL_SKIP"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }
}
