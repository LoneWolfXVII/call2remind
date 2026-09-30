package app.call2remind.scheduling

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
     */
    suspend fun replan(reason: ReplanReason, lookback: Duration = Duration.ZERO): ReplanResult = mutex.withLock {
        val now = clock.instant()
        val current = settings.current()
        val planner = OccurrencePlanner(clock, current.defaultTimes, OccurrencePlanner.DEFAULT_WINDOW, lookback)
        val plan = planner.plan(reminders.getAll(), occurrences.getForPlanning(now.minus(lookback)), now)
        val applied = if (plan.hasChanges) {
            occurrences.applyPlan(plan)
        } else {
            null
        }
        plan.toCancel.forEach { alarms.cancel(it.occurrence) }
        val armed = reconcileLocked(now, armOverdue = false)
        ReplanResult(
            reason = reason,
            created = applied?.created.orEmpty(),
            deleted = applied?.deleted.orEmpty(),
            armed = armed,
        )
    }

    /**
     * Arms the alarm of every SCHEDULED/SNOOZED occurrence due at or after now (soonest first,
     * capped at [MAX_ARMED_ALARMS]). With [armOverdue], overdue ones are armed to fire
     * immediately so the alarm receiver rings them (recovery path). Returns the number armed.
     */
    suspend fun reconcile(armOverdue: Boolean = false): Int = mutex.withLock {
        reconcileLocked(clock.instant(), armOverdue)
    }

    private suspend fun reconcileLocked(now: Instant, armOverdue: Boolean): Int {
        val targets = occurrences.getPending()
            .mapNotNull { occurrence ->
                when {
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
     * its alarm if it no longer needs one, re-arms (a snooze gets its new alarm), and posts or
     * clears missed notifications. Returns `null` if the occurrence does not exist.
     */
    suspend fun handle(occurrenceId: String, event: OccurrenceEvent): TransitionResult? {
        val result = occurrences.transition(occurrenceId, event) ?: return null
        if (result is TransitionResult.Transitioned) {
            val occurrence = result.occurrence
            val needsAlarm = occurrence.state == OccurrenceState.SCHEDULED || occurrence.state == OccurrenceState.SNOOZED
            if (!needsAlarm) alarms.cancel(occurrence)
            when {
                occurrence.state == OccurrenceState.MISSED || event == OccurrenceEvent.RingTimeout ->
                    missedNotifier.onMissed(occurrence)
                occurrence.state == OccurrenceState.DONE || occurrence.state == OccurrenceState.SKIPPED ->
                    missedNotifier.onResolved(occurrence)
                else -> Unit
            }
            reconcile()
        }
        return result
    }

    /**
     * Applies [RecoveryPolicy]: marks too-late rings missed, times out rings stuck ringing and
     * finishes stale answered calls. `RingNow` actions are left pending (returned) for the ring
     * queue / an immediate alarm to ring.
     */
    suspend fun recover(): List<RecoveryAction> {
        val policy = RecoveryPolicy(snoozePolicy = settings.current().snoozePolicy)
        val actions = policy.recover(occurrences.getActive(), clock.instant())
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
     * Ring queue step: recovers stale rings, then claims the ring lock for the next due
     * occurrence ([RingQueue] order). Returns [RingStep.Defer] (logging DEFERRED) while the user
     * is in a real phone call; the occurrences stay pending.
     */
    suspend fun claimNext(): RingStep {
        recover()
        val now = clock.instant()
        val active = occurrences.getActive()
        if (RingQueue.isLineBusy(active)) return RingStep.LineBusy
        val due = RingQueue.due(active, now)
        if (due.isEmpty()) return RingStep.Idle
        if (ringContext.current().inRealCall) {
            due.forEach { occurrences.appendLog(RingLogEvent(it.id, RingLogType.DEFERRED, now, RingLogEvent.REASON_IN_CALL)) }
            return RingStep.Defer(due)
        }
        for (candidate in due) {
            if (occurrences.tryClaimRing(candidate.id, now)) {
                alarms.cancel(candidate)
                val claimed = occurrences.get(candidate.id) ?: continue
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
    suspend fun ringImmediately(reminder: Reminder, plannedAt: Instant, sourceId: String? = null): Occurrence {
        val stored = reminders.upsert(reminder, sourceId)
        val occurrence = Occurrence.scheduled(stored, plannedAt)
        occurrences.insertIfAbsent(occurrence)
        val current = occurrences.get(occurrence.id) ?: occurrence
        if (current.state == OccurrenceState.SCHEDULED || current.state == OccurrenceState.SNOOZED) {
            val now = clock.instant()
            alarms.arm(current, if (current.fireAt.isAfter(now)) current.fireAt else now, isSoonest = true)
        }
        return current
    }

    companion object {
        /** How far back the boot path plans; matches the "ring if < 2 h late" recovery rule. */
        val BOOT_LOOKBACK: Duration = RecoveryPolicy().ringIfLateWithin

        /** Terminal occurrences and log entries older than this are pruned daily. */
        val HISTORY_RETENTION: Duration = Duration.ofDays(30)

        /** Upper bound on simultaneously armed alarms (AlarmManager caps apps at 500). */
        const val MAX_ARMED_ALARMS: Int = 200
    }
}
