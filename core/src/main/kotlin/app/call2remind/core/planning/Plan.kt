package app.call2remind.core.planning

import app.call2remind.core.model.Occurrence

/** Why the planner wants an occurrence cancelled. */
enum class CancelReason {
    /** Its reminder no longer exists. */
    REMINDER_REMOVED,

    /** Its reminder was disabled. */
    REMINDER_DISABLED,

    /** Its reminder's schedule (or the default time) changed and no longer produces it. */
    SCHEDULE_CHANGED,
}

/** An occurrence the planner wants cancelled, with the reason. */
data class Cancellation(val occurrence: Occurrence, val reason: CancelReason)

/**
 * Result of [OccurrencePlanner.plan].
 *
 * The app applies it in one transaction: insert [toCreate] and arm their alarms, cancel the
 * alarms of [toCancel] and **delete** those rows (deleting rather than marking them terminal
 * lets a re-enabled reminder be planned again with the same ids), and leave [toKeep] untouched.
 */
data class Plan(
    val toCreate: List<Occurrence>,
    val toCancel: List<Cancellation>,
    val toKeep: List<Occurrence>,
) {
    /** True when applying this plan would change nothing. */
    val hasChanges: Boolean get() = toCreate.isNotEmpty() || toCancel.isNotEmpty()

    /**
     * Returns [existing] with this plan applied (cancelled removed, created appended).
     * Mirrors what the app does to its table; mainly for tests and in-memory callers.
     */
    fun applyTo(existing: List<Occurrence>): List<Occurrence> {
        val cancelledIds = toCancel.mapTo(HashSet()) { it.occurrence.id }
        return existing.filter { it.id !in cancelledIds } + toCreate
    }
}
