package app.call2remind.core.planning

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceKey
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
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
 *   can ring or miss them. All instants have millisecond precision (see [ScheduleExpander]).
 * - A wanted occurrence is created unless an occurrence with the same id (= dedupe key
 *   `sourceType + externalId + plannedAt`) exists **in any state** and is not being cancelled by
 *   this plan, so done/skipped/missed rings are never resurrected.
 * - An existing occurrence belongs to a reminder by its [Occurrence.reminderId] or, failing that,
 *   by its dedupe identity (`sourceType + externalId`, read from the id). An enabled reminder that
 *   still produces it (same id) **owns** it; if the owner's local id differs from
 *   [Occurrence.reminderId] (the reminder was re-created under a new local id, or one of two twin
 *   reminders was removed) the occurrence is re-pointed via [Plan.toUpdate], keeping its state.
 * - An existing SCHEDULED or SNOOZED occurrence nobody owns is cancelled:
 *   - [CancelReason.REMINDER_REMOVED] / [CancelReason.REMINDER_DISABLED] always;
 *   - [CancelReason.SCHEDULE_CHANGED] only when it is SCHEDULED and not yet due (`fireAt > now`).
 *     A SNOOZED or due/overdue SCHEDULED one is kept: the user asked to be called again, or
 *     recovery must still ring or miss it, and the new schedule may never recreate a past ring.
 * - RINGING and terminal occurrences are always kept (re-pointed if needed): the state machine
 *   owns the ringing one and terminal ones are history.
 *
 * **Input contract:** `existing` must contain every active occurrence plus every occurrence, in
 * any state, whose `plannedAt >= now - lookback`. Terminal rows planned before that may be pruned
 * or omitted; terminal rows inside it must not be, or they would be planned again.
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

    private data class Identity(val sourceType: SourceType, val externalId: String)

    /** Computes the plan for [reminders] against [existing] occurrences at [now]. */
    fun plan(
        reminders: Collection<Reminder>,
        existing: Collection<Occurrence>,
        now: Instant = clock.instant(),
    ): Plan {
        val remindersById = reminders.associateBy { it.id }
        val enabledByIdentity: Map<Identity, List<Reminder>> = reminders
            .filter { it.enabled }
            .groupBy { Identity(it.sourceType, it.externalId) }
        val anyByIdentity: Set<Identity> = reminders.mapTo(HashSet()) { Identity(it.sourceType, it.externalId) }

        // Existing occurrences first, so cancelled ids can be planned again below.
        val toCancel = mutableListOf<Cancellation>()
        val toUpdate = mutableListOf<Occurrence>()
        val toKeep = mutableListOf<Occurrence>()
        for (occurrence in existing) {
            when (val decision = decide(occurrence, remindersById, enabledByIdentity, anyByIdentity, now)) {
                is Decision.Keep ->
                    if (decision.reminderId == occurrence.reminderId) {
                        toKeep += occurrence
                    } else {
                        toUpdate += occurrence.copy(reminderId = decision.reminderId)
                    }
                is Decision.Cancel -> toCancel += Cancellation(occurrence, decision.reason)
            }
        }
        val cancelledIds = toCancel.mapTo(HashSet()) { it.occurrence.id }
        val occupiedIds = existing.mapNotNullTo(HashSet()) { if (it.id in cancelledIds) null else it.id }

        val from = now.minus(lookback)
        val to = now.plus(window)
        val toCreate = LinkedHashMap<String, Occurrence>()
        for (reminder in reminders) {
            if (!reminder.enabled) continue
            for (instant in expander.fireTimes(reminder, from, to)) {
                val occurrence = Occurrence.scheduled(reminder, instant)
                if (occurrence.id !in occupiedIds && occurrence.id !in toCreate) {
                    toCreate[occurrence.id] = occurrence
                }
            }
        }

        return Plan(
            toCreate = toCreate.values.sortedWith(compareBy({ it.fireAt }, { it.id })),
            toCancel = toCancel,
            toKeep = toKeep,
            toUpdate = toUpdate,
        )
    }

    private sealed interface Decision {
        /** Keep, owned by [reminderId] (differs from the stored one → re-point). */
        data class Keep(val reminderId: String) : Decision

        data class Cancel(val reason: CancelReason) : Decision
    }

    private fun decide(
        occurrence: Occurrence,
        remindersById: Map<String, Reminder>,
        enabledByIdentity: Map<Identity, List<Reminder>>,
        anyByIdentity: Set<Identity>,
        now: Instant,
    ): Decision {
        val byId = remindersById[occurrence.reminderId]
        val identity = OccurrenceKey.parse(occurrence.id)?.let { Identity(it.sourceType, it.externalId) }
        val twins = identity?.let { enabledByIdentity[it] }.orEmpty()
        val candidates = (listOfNotNull(byId?.takeIf { it.enabled }) + twins).distinctBy { it.id }

        // Prefer the stored owner, then twins in input order.
        candidates.firstOrNull { produces(it, occurrence) }?.let { return Decision.Keep(it.id) }

        // Nobody produces it any more. The fallback owner (for re-pointing kept rows) is the stored
        // reminder if it still exists, otherwise an enabled twin.
        val fallbackOwner = byId?.id ?: twins.firstOrNull()?.id ?: occurrence.reminderId
        val state = occurrence.state
        if (state != OccurrenceState.SCHEDULED && state != OccurrenceState.SNOOZED) {
            return Decision.Keep(fallbackOwner)
        }
        val reason = when {
            candidates.isNotEmpty() -> CancelReason.SCHEDULE_CHANGED
            byId != null -> CancelReason.REMINDER_DISABLED
            identity != null && identity in anyByIdentity -> CancelReason.REMINDER_DISABLED
            else -> CancelReason.REMINDER_REMOVED
        }
        val dueOrSnoozed = state == OccurrenceState.SNOOZED || !occurrence.fireAt.isAfter(now)
        return if (reason == CancelReason.SCHEDULE_CHANGED && dueOrSnoozed) {
            Decision.Keep(fallbackOwner)
        } else {
            Decision.Cancel(reason)
        }
    }

    /** True if [reminder] (as it is now) plans exactly [occurrence]'s id. */
    private fun produces(reminder: Reminder, occurrence: Occurrence): Boolean =
        reminder.sourceType == occurrence.sourceType &&
            OccurrenceKey.of(reminder, occurrence.plannedAt).id == occurrence.id &&
            expander.firesAt(reminder, occurrence.plannedAt)

    companion object {
        /** Default alarm window: 48 hours. */
        val DEFAULT_WINDOW: Duration = Duration.ofHours(48)
    }
}
