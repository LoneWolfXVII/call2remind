package app.call2remind.core.planning

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.time.DefaultTimes
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Reconciles reminders with stored occurrences for the rolling alarm window.
 *
 * Rules:
 * - For each **enabled** reminder, ring instants in **[now - lookback, now + window)** (start
 *   inclusive, end exclusive) are wanted. With the default `lookback = 0`, past rings are never
 *   created; the boot path may pass a lookback so [app.call2remind.core.ringing.RecoveryPolicy]
 *   can ring or miss them.
 * - A wanted occurrence is created unless an occurrence with the same id (= dedupe key
 *   `sourceType + externalId + plannedAt`) exists **in any state**, so done/skipped/missed rings
 *   are never resurrected.
 * - An existing SCHEDULED or SNOOZED occurrence is cancelled when its reminder was removed or
 *   disabled, or its reminder's schedule no longer produces its [Occurrence.plannedAt]. Whether
 *   it is inside the window does not matter (an overdue SCHEDULED one is left for recovery).
 * - RINGING and terminal occurrences are always kept: the state machine owns the ringing one and
 *   terminal ones are history.
 *
 * Planning is idempotent: applying a plan and planning again with the same inputs yields a plan
 * without changes.
 */
class OccurrencePlanner(
    private val clock: Clock,
    private val defaultTimes: DefaultTimes = DefaultTimes.DEFAULT,
    private val window: Duration = DEFAULT_WINDOW,
    private val lookback: Duration = Duration.ZERO,
) {
    init {
        require(!window.isNegative && !window.isZero) { "window must be positive, was $window" }
        require(!lookback.isNegative) { "lookback must be >= 0, was $lookback" }
    }

    private val expander = ScheduleExpander(defaultTimes)

    /** Computes the plan for [reminders] against [existing] occurrences at [now]. */
    fun plan(
        reminders: Collection<Reminder>,
        existing: Collection<Occurrence>,
        now: Instant = clock.instant(),
    ): Plan {
        val remindersById = reminders.associateBy { it.id }
        val existingIds = existing.mapTo(HashSet()) { it.id }

        val from = now.minus(lookback)
        val to = now.plus(window)
        val toCreate = LinkedHashMap<String, Occurrence>()
        for (reminder in reminders) {
            if (!reminder.enabled) continue
            for (instant in expander.fireTimes(reminder, from, to)) {
                val occurrence = Occurrence.scheduled(reminder, instant)
                if (occurrence.id !in existingIds && occurrence.id !in toCreate) {
                    toCreate[occurrence.id] = occurrence
                }
            }
        }

        val toCancel = mutableListOf<Cancellation>()
        val toKeep = mutableListOf<Occurrence>()
        for (occurrence in existing) {
            val reason = cancelReason(occurrence, remindersById[occurrence.reminderId])
            if (reason == null) toKeep += occurrence else toCancel += Cancellation(occurrence, reason)
        }

        return Plan(
            toCreate = toCreate.values.sortedWith(compareBy({ it.fireAt }, { it.id })),
            toCancel = toCancel,
            toKeep = toKeep,
        )
    }

    private fun cancelReason(occurrence: Occurrence, reminder: Reminder?): CancelReason? {
        if (occurrence.state != OccurrenceState.SCHEDULED && occurrence.state != OccurrenceState.SNOOZED) {
            return null
        }
        return when {
            reminder == null -> CancelReason.REMINDER_REMOVED
            !reminder.enabled -> CancelReason.REMINDER_DISABLED
            reminder.sourceType != occurrence.sourceType -> CancelReason.SCHEDULE_CHANGED
            !expander.firesAt(reminder, occurrence.plannedAt) -> CancelReason.SCHEDULE_CHANGED
            Occurrence.scheduled(reminder, occurrence.plannedAt).id != occurrence.id -> CancelReason.SCHEDULE_CHANGED
            else -> null
        }
    }

    companion object {
        /** Default alarm window: 48 hours. */
        val DEFAULT_WINDOW: Duration = Duration.ofHours(48)
    }
}
