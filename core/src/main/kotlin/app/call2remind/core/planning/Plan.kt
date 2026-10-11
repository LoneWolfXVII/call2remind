package app.call2remind.core.planning

import app.call2remind.core.model.Occurrence

/** Why the planner wants an occurrence cancelled. */
enum class CancelReason {
    /** Its reminder no longer exists. */
    REMINDER_REMOVED,

    /** Its reminder was disabled. */
    REMINDER_DISABLED,

    /**
     * Its reminder's schedule (or the default time) changed and no longer produces it. Only used
     * for SCHEDULED occurrences that are not yet due; snoozed and overdue ones are kept.
     */
    SCHEDULE_CHANGED,
}

/** An occurrence the planner wants cancelled, with the reason. */
data class Cancellation(val occurrence: Occurrence, val reason: CancelReason)

/**
 * Result of [OccurrencePlanner.plan].
 *
 * The app applies it in one transaction, **in this order**:
 * 1. cancel the alarms of [toCancel] and **delete** those rows (deleting rather than marking them
 *    terminal lets a re-enabled reminder be planned again with the same ids);
 * 2. rewrite the `reminderId` of [toUpdate] rows (nothing else about them changes);
 * 3. insert [toCreate] and arm their alarms.
 *
 * Deletes must come before inserts: [toCreate] may contain an id that is also in [toCancel]
 * (e.g. a disabled reminder's occurrence re-planned for an enabled twin). [toKeep] is untouched.
 */
data class Plan(
    val toCreate: List<Occurrence>,
    val toCancel: List<Cancellation>,
    val toKeep: List<Occurrence>,
    /**
     * Existing occurrences re-pointed to another local reminder with the same
     * `sourceType + externalId` (the reminder was re-created under a new id, or a twin was
     * removed). Holds the updated occurrences; only [Occurrence.reminderId] differs from the stored
     * row, so apps should update just that column.
     */
    val toUpdate: List<Occurrence> = emptyList(),
) {
    /** True when applying this plan would change nothing. */
    val hasChanges: Boolean get() = toCreate.isNotEmpty() || toCancel.isNotEmpty() || toUpdate.isNotEmpty()

    /**
     * Returns [existing] with this plan applied (cancelled removed, updated replaced, created
     * appended). Mirrors what the app does to its table; mainly for tests and in-memory callers.
     */
    fun applyTo(existing: List<Occurrence>): List<Occurrence> {
        val cancelledIds = toCancel.mapTo(HashSet()) { it.occurrence.id }
        val updatesById = toUpdate.associateBy { it.id }
        val kept = existing.filter { it.id !in cancelledIds }.map { updatesById[it.id] ?: it }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        return kept + toCreate.filter { it.id !in keptIds }
    }
}
