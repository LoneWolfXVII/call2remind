package app.call2remind.core.recurrence

import java.time.LocalDate

/** When a [RecurrenceRule] stops producing occurrences. */
sealed interface RecurrenceEnd {
    /** Repeats forever. */
    data object Never : RecurrenceEnd

    /** Last occurrence is on [date] (inclusive, in the rule's zone). */
    data class Until(val date: LocalDate) : RecurrenceEnd

    /** Stops after [count] occurrences in total (each time on each day counts once). */
    data class Count(val count: Int) : RecurrenceEnd {
        init {
            require(count >= 1) { "count must be >= 1, was $count" }
        }
    }
}
