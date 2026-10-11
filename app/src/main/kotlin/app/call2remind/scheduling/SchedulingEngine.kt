package app.call2remind.scheduling

import android.util.Log
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.planning.OccurrencePlanner
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.RecoveryAction
import app.call2remind.core.ringing.RecoveryPolicy
import app.call2remind.core.ringing.RingQueue
import app.call2remind.core.ringing.TransitionResult
import app.call2remind.core.time.DeviceZonePolicy
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.ringing.RingContextProvider
import app.call2remind.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Why a replan runs (for logging / tests). */
enum class ReplanReason {
    APP_START,
    BOOT,
    PACKAGE_REPLACED,
    TIME_CHANGE,
    SYNC,
    REMINDER_CHANGED,
    SETTINGS_CHANGED,
    DAILY_TOP_UP,
    MANUAL,
}

/** Result of [SchedulingEngine.replan]. */
data class ReplanResult(
    val reason: ReplanReason,
    val created: List<Occurrence>,
    val deleted: List<Occurrence>,
    val armed: Int,
)

/** What [SchedulingEngine.claimNext] decided. */
sealed interface RingStep {
    /** [occurrence] is now RINGING (lock held): present it. */
    data class Ring(val occurrence: Occurrence) : RingStep

    /** Occurrences are due but the user is in a real call: post a silent heads-up and wait. */
    data class Defer(val due: List<Occurrence>) : RingStep

    /** Something is already ringing; it will pick up the queue when it ends. */
    data object LineBusy : RingStep

    /** Nothing is due. */
    data object Idle : RingStep
}

/**
 * Orchestrates planning, alarms and state changes. Android-free apart from interfaces, so it is
 * fully testable with fakes.
 *
 * Entry points:
 * - [replan]: reminders → [OccurrencePlanner] → delete cancelled, insert created, re-arm. Call after
 *   any reminder/source/settings change, on app start, time/zone change and daily.
 * - [reconcile]: re-arms the alarm of every pending occurrence (idempotent).
 * - [handle]: applies a user/system event (answer, decline, snooze, done…) transactionally, then
 *   fixes alarms and notifications.
 * - [claimNext]: takes the ring lock for the next due occurrence (ring queue).
 * - [recoverAfterBoot] / [runWatchdog]: [RecoveryPolicy] for rings that should already have happened.
 */
@Singleton
class SchedulingEngine @Inject constructor(
    private val reminders: ReminderRepository,
    private val occurrences: OccurrenceRepository,
    private val alarms: AlarmScheduler,
    private val settings: SettingsRepository,
    private val ringContext: RingContextProvider,
    private val missedNotifier: MissedNotifier,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /**
     * Plans the rolling window and applies the plan. With a [lookback] (boot), rings planned in
     * `[now - lookback, now)` that never got a row are created too, for recovery to handle.
     *
     * First, reminders that follow the device zone ([DeviceZonePolicy]: synced date-only sources,
     * and habits per its switch) but are stored with another zone are moved to the clock's
     * current zone and saved, so they keep their local wall time after a zone change. Doing it
     * here, under the scheduling lock, means every replan (zone change, app start, and the replan
     * after each sync — even a sync that read the old zone before the change) converges on the
     * current zone without waiting for a re-sync. SNOOZED / RINGING occurrences are kept by the
     * planner; future SCHEDULED ones move to the new wall-clock instant.
     */
    suspend fun replan(reason: ReplanReason, lookback: Duration = Duration.ZERO): ReplanResult = mutex.withLock {
        val now = clock.instant()
        val current = settings.current()
        val planner = OccurrencePlanner(clock, current.defaultTimes, OccurrencePlanner.DEFAULT_WINDOW, lookback)
        val snapshot = reminders.getAllForPlanning().let { it.copy(reminders = followDeviceZone(it.reminders)) }
        val existing = occurrences.getForPlanning(now.minus(lookback)).let { rows ->
            if (snapshot.unreadableIds.isEmpty()) {
                rows
            } else {
                // A corrupt reminder row is not a removed reminder: keep its occurrences as they are
                // (they still ring, with a fallback title) instead of cancelling them.
                Log.w(TAG, "Not planning ${snapshot.unreadableIds.size} unreadable reminder(s): ${snapshot.unreadableIds}")
                rows.filterNot { it.reminderId in snapshot.unreadableIds }
            }
        }
        val plan = planner.plan(snapshot.reminders, existing, now)
        val applied = if (plan.hasChanges) {
            occurrences.applyPlan(plan)
        } else {
            null
        }
        // Only rows really deleted: one that started ringing meanwhile keeps its (already cancelled) alarm.
        applied?.deleted?.forEach { alarms.cancel(it) }
        val armed = reconcileLocked(now, armOverdue = false)
        ReplanResult(
            reason = reason,
            created = applied?.created.orEmpty(),
            deleted = applied?.deleted.orEmpty(),
            armed = armed,
        )
    }

    /**
     * Arms the alarm of every SCHEDULED/SNOOZED occurrence due at or after now, and of every
     * RINGING occurrence at its ring deadline ([RecoveryPolicy.deadline] + [DEADLINE_SLACK], or
     * now if already past), soonest first, capped at [MAX_ARMED_ALARMS]. With [armOverdue],
     * overdue pending ones are armed to fire immediately so the alarm receiver rings them
     * (recovery path). Returns the number armed.
     *
     * The deadline alarm of a ringing occurrence is what frees the line promptly when its ringing
     * service died or never started: it runs the receiver's recovery + ring queue, which times the
     * ring out (or finishes an abandoned answered call) and rings whatever queued up behind it.
     */
    suspend fun reconcile(armOverdue: Boolean = false): Int = mutex.withLock {
        reconcileLocked(clock.instant(), armOverdue)
    }

    private suspend fun reconcileLocked(now: Instant, armOverdue: Boolean): Int {
        val policy = recoveryPolicy()
        val targets = occurrences.getActive()
            .mapNotNull { occurrence ->
                when {
                    occurrence.state == OccurrenceState.RINGING ->
                        policy.deadline(occurrence)?.let { occurrence to maxOf(it.plus(DEADLINE_SLACK), now) }
                    !occurrence.fireAt.isBefore(now) -> occurrence to occurrence.fireAt
                    armOverdue -> occurrence to now
                    else -> null
                }
            }
            .sortedWith(compareBy<Pair<Occurrence, Instant>>({ it.second }).thenBy(RingQueue.ORDER) { it.first })
            .take(MAX_ARMED_ALARMS)
        targets.forEachIndexed { index, (occurrence, at) -> alarms.arm(occurrence, at, isSoonest = index == 0) }
        return targets.size
    }

    /**
     * Applies [event] to occurrence [occurrenceId] (one transaction incl. ring log), then cancels
     * its alarm if it no longer needs one, re-arms (a snooze gets its new alarm, an answered ring
     * its new deadline), and posts or clears missed notifications. Returns `null` if the
     * occurrence does not exist.
     *
     * When the occurrence leaves RINGING the line is free: overdue occurrences that queued up
     * behind it (their alarms were consumed while the line was busy) get an immediate alarm, so
     * they ring even if no ringing service is alive to pump the queue.
     */
    suspend fun handle(occurrenceId: String, event: OccurrenceEvent): TransitionResult? {
        val result = occurrences.transition(occurrenceId, event) ?: return null
        if (result is TransitionResult.Transitioned) {
            val occurrence = result.occurrence
            val needsAlarm = occurrence.state.isActive
            val leftRinging = result.from == OccurrenceState.RINGING && occurrence.state != OccurrenceState.RINGING
            if (leftRinging) {
                missedNotifier.onRingEnded(occurrence)
            }
            when {
                occurrence.state == OccurrenceState.MISSED || event == OccurrenceEvent.RingTimeout ->
                    missedNotifier.onMissed(occurrence)
                occurrence.state == OccurrenceState.DONE || occurrence.state == OccurrenceState.SKIPPED ->
                    missedNotifier.onResolved(occurrence)
                else -> Unit
            }
            // Cancel + re-arm under the scheduling lock, so a concurrent reconcile that read the
            // old state cannot re-arm this occurrence's alarm after it was cancelled.
            mutex.withLock {
                if (!needsAlarm) alarms.cancel(occurrence)
                reconcileLocked(clock.instant(), armOverdue = leftRinging)
            }
        }
        return result
    }

    /**
     * Applies [RecoveryPolicy]: marks too-late rings missed, times out rings stuck ringing and
     * finishes stale answered calls. `RingNow` actions are left pending (returned) for the ring
     * queue / an immediate alarm to ring.
     */
    suspend fun recover(): List<RecoveryAction> {
        val actions = recoveryPolicy().recover(occurrences.getActive(), clock.instant())
        for (action in actions) {
            when (action) {
                is RecoveryAction.RingNow -> Unit
                is RecoveryAction.MarkMissed -> handle(action.occurrence.id, OccurrenceEvent.MarkMissed)
                is RecoveryAction.TimeOutRinging -> handle(action.occurrence.id, OccurrenceEvent.RingTimeout)
                is RecoveryAction.FinishAnswered -> handle(action.occurrence.id, OccurrenceEvent.Done)
            }
        }
        return actions
    }

    /**
     * Boot / package-replaced path: replan with a [BOOT_LOOKBACK] so rings missed while the phone
     * was off get rows, apply recovery, then arm everything — overdue-but-recent rings get an
     * immediate alarm, whose receiver claims the lock and rings (alarms may start the FGS).
     */
    suspend fun recoverAfterBoot(reason: ReplanReason = ReplanReason.BOOT): List<RecoveryAction> {
        replan(reason, BOOT_LOOKBACK)
        val actions = recover()
        reconcile(armOverdue = true)
        return actions
    }

    /**
     * Process start (every cold start, including ones caused by an alarm): replan the window,
     * then recovery + arm everything, so rings missed while the process was dead (e.g. killed
     * by the OEM) ring now or are marked missed. Idempotent and safe to race with the alarm
     * receiver: ringing always goes through the [OccurrenceRepository.tryClaimRing] lock.
     */
    suspend fun onAppStart(): ReplanResult {
        val result = replan(ReplanReason.APP_START)
        runWatchdog()
        return result
    }

    /**
     * Wall clock or time zone changed: wall-clock schedules resolve to new instants (replan; on a
     * zone change that includes moving device-zone reminders to the new zone, see [replan]) and
     * rings the jump made overdue are recovered (ring if < 2 h late, else missed).
     */
    suspend fun onTimeChanged(): ReplanResult {
        val result = replan(ReplanReason.TIME_CHANGE)
        runWatchdog()
        return result
    }

    /** Periodic watchdog: recovery, then arm everything (overdue → immediate alarm). */
    suspend fun runWatchdog(): List<RecoveryAction> {
        val actions = recover()
        reconcile(armOverdue = true)
        return actions
    }

    /** Daily top-up: replan the window and prune old history. */
    suspend fun dailyTopUp(): ReplanResult {
        val result = replan(ReplanReason.DAILY_TOP_UP)
        occurrences.pruneHistory(clock.instant().minus(HISTORY_RETENTION))
        return result
    }

    /**
     * Ring queue step: claims the ring lock for the next due occurrence ([RingQueue] order).
     * Recovery runs first only when it could change the outcome (a stale ring holds the line, or
     * a due occurrence is too late to ring), so the common path reaches the claim — and the
     * caller's foreground-service start — quickly, inside the alarm's start allowance.
     *
     * The claimed occurrence's alarm (just consumed by firing) is re-armed at its ring deadline,
     * so a ring whose service never starts or dies is recovered promptly.
     *
     * Returns [RingStep.Defer] (logging DEFERRED) while the user is in a real phone call; the
     * occurrences stay pending. On [RingStep.LineBusy] the ringing occurrence's deadline alarm
     * (armed by [reconcile], which callers run afterwards) pumps the queue if its service dies.
     */
    suspend fun claimNext(): RingStep {
        val policy = recoveryPolicy()
        var now = clock.instant()
        var active = occurrences.getActive()
        if (active.any { policy.needsRecoveryBeforeClaim(it, now) }) {
            recover()
            now = clock.instant()
            active = occurrences.getActive()
        }
        if (RingQueue.isLineBusy(active)) return RingStep.LineBusy
        val due = RingQueue.due(active, now)
        if (due.isEmpty()) return RingStep.Idle
        if (ringContext.current().inRealCall) {
            due.forEach { occurrences.appendLog(RingLogEvent(it.id, RingLogType.DEFERRED, now, RingLogEvent.REASON_IN_CALL)) }
            return RingStep.Defer(due)
        }
        for (candidate in due) {
            if (occurrences.tryClaimRing(candidate.id, now)) {
                val claimed = occurrences.get(candidate.id) ?: continue
                // Not under the mutex: a concurrent replan must not delay the ring. Re-arming is
                // idempotent, and reconcile re-derives the same deadline.
                policy.deadline(claimed)?.let { alarms.arm(claimed, it.plus(DEADLINE_SLACK), isSoonest = true) }
                return RingStep.Ring(claimed)
            }
        }
        return RingStep.LineBusy
    }

    /**
     * Stores [reminders] as the complete set of [sourceId] (deleting the source's others) and
     * replans. The one call a sync adapter needs after fetching.
     */
    suspend fun applySourceSnapshot(sourceId: String, reminders: List<Reminder>): ReplanResult {
        this.reminders.replaceForSource(sourceId, reminders)
        return replan(ReplanReason.SYNC)
    }

    /** Upserts reminders (e.g. a habit edit) and replans. */
    suspend fun upsertReminders(reminders: List<Reminder>, sourceId: String? = null): ReplanResult {
        this.reminders.upsertAll(reminders, sourceId)
        return replan(ReplanReason.REMINDER_CHANGED)
    }

    /** Deletes reminders and replans (their pending occurrences are deleted, alarms cancelled). */
    suspend fun deleteReminders(ids: Collection<String>): ReplanResult {
        reminders.delete(ids)
        return replan(ReplanReason.REMINDER_CHANGED)
    }

    /**
     * Reactive sources (Samsung notification listener): stores [reminder] and an occurrence
     * planned at [plannedAt] (normally the reminder's own `Schedule.At` instant), then arms an
     * immediate alarm; the alarm receiver claims the lock and rings, so this never double-rings.
     */
    suspend fun ringImmediately(reminder: Reminder, plannedAt: Instant, sourceId: String? = null): Occurrence = mutex.withLock {
        // Under the scheduling lock: a concurrent replan that read the reminders before this
        // upsert must not then see the new occurrence and cancel it as REMINDER_REMOVED.
        val stored = reminders.upsert(reminder, sourceId)
        val occurrence = Occurrence.scheduled(stored, plannedAt)
        occurrences.insertIfAbsent(occurrence)
        val current = occurrences.get(occurrence.id) ?: occurrence
        if (current.state == OccurrenceState.SCHEDULED || current.state == OccurrenceState.SNOOZED) {
            val now = clock.instant()
            alarms.arm(current, if (current.fireAt.isAfter(now)) current.fireAt else now, isSoonest = true)
        }
        current
    }

    /** [all] with device-zone followers moved (and saved) to the current zone; see [replan]. */
    private suspend fun followDeviceZone(all: List<Reminder>): List<Reminder> {
        val zone = clock.zone
        val moved = DeviceZonePolicy.toDeviceZone(all, zone)
        if (moved.isEmpty()) return all
        reminders.setZone(moved.map { it.id }, zone)
        Log.i(TAG, "Moved ${moved.size} reminder(s) to the device zone $zone")
        val byId = moved.associateBy { it.id }
        return all.map { byId[it.id] ?: it }
    }

    /** Recovery would mark, time out or finish [occurrence] (anything but "ring it now"). */
    private fun RecoveryPolicy.needsRecoveryBeforeClaim(occurrence: Occurrence, now: Instant): Boolean {
        val action = actionFor(occurrence, now)
        return action != null && action !is RecoveryAction.RingNow
    }

    private suspend fun recoveryPolicy(): RecoveryPolicy = RecoveryPolicy(snoozePolicy = settings.current().snoozePolicy)

    companion object {
        private const val TAG = "SchedulingEngine"

        /** A ring's deadline alarm fires this long after [RecoveryPolicy.deadline], so recovery acts on it. */
        val DEADLINE_SLACK: Duration = Duration.ofSeconds(1)

        /** How far back the boot path plans; matches the "ring if < 2 h late" recovery rule. */
        val BOOT_LOOKBACK: Duration = RecoveryPolicy().ringIfLateWithin

        /** Terminal occurrences and log entries older than this are pruned daily. */
        val HISTORY_RETENTION: Duration = Duration.ofDays(30)

        /** Upper bound on simultaneously armed alarms (AlarmManager caps apps at 500). */
        const val MAX_ARMED_ALARMS: Int = 200
    }
}
