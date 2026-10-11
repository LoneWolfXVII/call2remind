package app.call2remind.core.recurrence

import app.call2remind.core.time.resolveLocal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Weekly habit recurrence: on [daysOfWeek] at each of [times], every [intervalWeeks] weeks,
 * starting on [startDate].
 *
 * Weeks are ISO weeks (Monday first). The week containing [startDate] is week 0; with
 * `intervalWeeks = 2` weeks 0, 2, 4… are active. Dates before [startDate] never match.
 *
 * Wall-clock times are resolved with [resolveLocal] (DST gap → forward by the gap, overlap →
 * earlier offset). If two times collapse onto the same instant (e.g. 02:30 and 03:30 on a
 * spring-forward day in New York) the instant is produced once.
 */
data class RecurrenceRule(
    val daysOfWeek: Set<DayOfWeek>,
    val times: Set<LocalTime>,
    val startDate: LocalDate,
    val intervalWeeks: Int = 1,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
) {
    init {
        require(daysOfWeek.isNotEmpty()) { "daysOfWeek must not be empty" }
        require(times.isNotEmpty()) { "times must not be empty" }
        require(intervalWeeks >= 1) { "intervalWeeks must be >= 1, was $intervalWeeks" }
        if (end is RecurrenceEnd.Until) {
            require(!end.date.isBefore(startDate)) { "until ${end.date} is before start $startDate" }
        }
    }

    private val sortedTimes: List<LocalTime> = times.sorted()
    private val anchorMonday: LocalDate =
        startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** True if the rule is active on [date] (ignores the count limit). */
    fun matchesDate(date: LocalDate): Boolean {
        if (date.isBefore(startDate)) return false
        if (end is RecurrenceEnd.Until && date.isAfter(end.date)) return false
        if (date.dayOfWeek !in daysOfWeek) return false
        val weekIndex = ChronoUnit.DAYS.between(anchorMonday, date) / DAYS_PER_WEEK
        return weekIndex % intervalWeeks == 0L
    }

    /**
     * All ring instants in the half-open window **[from, to)** (from inclusive, to exclusive),
     * sorted ascending, without duplicates. Returns an empty list if `to <= from`.
     */
    fun occurrencesBetween(from: Instant, to: Instant, zone: ZoneId): List<Instant> {
        if (!to.isAfter(from)) return emptyList()
        val countLimit = (end as? RecurrenceEnd.Count)?.count
        // With a count limit we must walk from the start to know which instants are within it.
        // Otherwise one day of margin on each side covers any zone offset / gap shift.
        val firstDate = if (countLimit != null) {
            startDate
        } else {
            maxOf(startDate, from.atZone(zone).toLocalDate().minusDays(1))
        }
        var lastDate = to.atZone(zone).toLocalDate().plusDays(1)
        if (end is RecurrenceEnd.Until && end.date.isBefore(lastDate)) lastDate = end.date

        val result = mutableListOf<Instant>()
        var lastEmitted: Instant? = null
        var emitted = 0
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            if (matchesDate(date)) {
                val instants = sortedTimes.map { resolveLocal(date, it, zone) }.sorted()
                for (instant in instants) {
                    val previous = lastEmitted
                    if (previous != null && !instant.isAfter(previous)) continue
                    lastEmitted = instant
                    emitted++
                    if (countLimit != null && emitted > countLimit) return result
                    if (!instant.isBefore(to)) return result
                    if (!instant.isBefore(from)) result += instant
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    private companion object {
        const val DAYS_PER_WEEK = 7L
    }
}
