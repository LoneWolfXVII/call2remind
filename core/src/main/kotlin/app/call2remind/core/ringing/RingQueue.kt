package app.call2remind.core.ringing

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import java.time.Instant

/**
 * Picks which due occurrence rings next. Only one occurrence rings at a time: while any
 * occurrence is RINGING the line is busy and [next] returns `null`.
 *
 * Order ([ORDER]): earliest [Occurrence.fireAt], then lowest
 * [app.call2remind.core.model.SourceType.ringPriority], then [Occurrence.id] (lexicographic).
 */
object RingQueue {

    /** Deterministic ring order. */
    val ORDER: Comparator<Occurrence> =
        compareBy<Occurrence>({ it.fireAt }, { it.sourceType.ringPriority }, { it.id })

    /** SCHEDULED/SNOOZED occurrences with `fireAt <= now`, in ring order. */
    fun due(occurrences: Collection<Occurrence>, now: Instant): List<Occurrence> =
        occurrences
            .filter { it.isPending() && !it.fireAt.isAfter(now) }
            .sortedWith(ORDER)

    /** True if some occurrence is currently ringing. */
    fun isLineBusy(occurrences: Collection<Occurrence>): Boolean =
        occurrences.any { it.state == OccurrenceState.RINGING }

    /** The occurrence to ring now, or `null` if the line is busy or nothing is due. */
    fun next(occurrences: Collection<Occurrence>, now: Instant): Occurrence? =
        if (isLineBusy(occurrences)) null else due(occurrences, now).firstOrNull()

    private fun Occurrence.isPending(): Boolean =
        state == OccurrenceState.SCHEDULED || state == OccurrenceState.SNOOZED
}
