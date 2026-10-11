package app.call2remind.core.planning

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.time.DefaultTimes
import app.call2remind.core.time.resolveLocal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Expands a [Reminder]'s [Schedule] into concrete ring instants (lead offset already applied).
 *
 * Ring instants are **truncated to milliseconds** (the precision of occurrence ids and of Room),
 * so a stored occurrence's `plannedAt` always equals the instant the expander produces for it.
 *
 * Date-only schedules ring at their explicit time or, if absent, at [defaultTimes] for the
 * reminder's source.
 */
class ScheduleExpander(private val defaultTimes: DefaultTimes = DefaultTimes.DEFAULT) {

    /**
     * Ring instants of [reminder] in **[from, to)** (compared after truncation to milliseconds),
     * sorted ascending and distinct. Ignores [Reminder.enabled]; callers decide whether disabled
     * reminders matter.
     */
    fun fireTimes(reminder: Reminder, from: Instant, to: Instant): List<Instant> =
        rings(reminder, from, to).map { it.fireAt }.distinct()

    /** True if [reminder]'s schedule produces a ring at [instant] (compared at millisecond precision). */
    fun firesAt(reminder: Reminder, instant: Instant): Boolean {
        val millis = instant.truncatedTo(ChronoUnit.MILLIS)
        return fireTimes(reminder, millis, millis.plusMillis(1)).isNotEmpty()
    }

    /**
     * The scheduled (event) moment whose ring is [fireAt], i.e. the base instant *before* the lead
     * offset was applied, or `null` if [reminder]'s schedule does not ring at [fireAt].
     *
     * Unlike [app.call2remind.core.model.LeadOffset.eventTimeFor] this is exact even when the
     * lead's calendar-day shift lands in a DST gap, because it re-expands the schedule instead of
     * inverting the offset. If several events ring at the same instant, the earliest is returned.
     */
    fun eventTimeFor(reminder: Reminder, fireAt: Instant): Instant? {
        val millis = fireAt.truncatedTo(ChronoUnit.MILLIS)
        return rings(reminder, millis, millis.plusMillis(1)).firstOrNull()?.eventAt
    }

    /** One ring: the event moment and its (millisecond) ring instant. */
    private data class Ring(val eventAt: Instant, val fireAt: Instant)

    private fun rings(reminder: Reminder, from: Instant, to: Instant): List<Ring> {
        if (!to.isAfter(from)) return emptyList()
        val schedule = reminder.schedule
        val zone = reminder.zone
        // A ring at `fire` belongs to an event at base >= fire, base < to + maxSpan
        // (+1 ms: truncation can move a ring up to 1 ms earlier than its exact instant).
        val baseTo = to.plus(schedule.lead.maxSpan).plusMillis(1)
        val bases: List<Instant> = when (schedule) {
            is Schedule.At -> listOf(schedule.instant)
            is Schedule.DateOnly -> listOf(
                resolveLocal(schedule.date, schedule.time ?: defaultTimes[reminder.sourceType], zone),
            )
            is Schedule.Recurring -> schedule.rule.occurrencesBetween(from, baseTo, zone)
            is Schedule.Annual -> annualBases(reminder, schedule, from, baseTo, zone)
        }
        return bases
            .map { Ring(it, schedule.lead.applyTo(it, zone).truncatedTo(ChronoUnit.MILLIS)) }
            .filter { !it.fireAt.isBefore(from) && it.fireAt.isBefore(to) }
            .sortedWith(compareBy({ it.fireAt }, { it.eventAt }))
    }

    private fun annualBases(
        reminder: Reminder,
        schedule: Schedule.Annual,
        from: Instant,
        to: Instant,
        zone: ZoneId,
    ): List<Instant> {
        val time = schedule.time ?: defaultTimes[reminder.sourceType]
        val firstYear = maxOf(from.atZone(zone).year - 1, schedule.sinceYear ?: Int.MIN_VALUE)
        val lastYear = to.atZone(zone).year + 1
        if (firstYear > lastYear) return emptyList()
        // MonthDay.atYear maps Feb 29 to Feb 28 in non-leap years.
        return (firstYear..lastYear).map { year -> resolveLocal(schedule.monthDay.atYear(year), time, zone) }
    }
}
