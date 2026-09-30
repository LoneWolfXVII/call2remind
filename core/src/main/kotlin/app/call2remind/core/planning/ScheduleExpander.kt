package app.call2remind.core.planning

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.time.DefaultTimes
import app.call2remind.core.time.resolveLocal
import java.time.Instant
import java.time.ZoneId

/**
 * Expands a [Reminder]'s [Schedule] into concrete ring instants (lead offset already applied).
 *
 * Date-only schedules ring at their explicit time or, if absent, at [defaultTimes] for the
 * reminder's source.
 */
class ScheduleExpander(private val defaultTimes: DefaultTimes = DefaultTimes.DEFAULT) {

    /**
     * Ring instants of [reminder] in **[from, to)**, sorted ascending and distinct.
     * Ignores [Reminder.enabled]; callers decide whether disabled reminders matter.
     */
    fun fireTimes(reminder: Reminder, from: Instant, to: Instant): List<Instant> {
        if (!to.isAfter(from)) return emptyList()
        val schedule = reminder.schedule
        val zone = reminder.zone
        // A ring at `fire` belongs to an event at base >= fire, base < to + maxSpan.
        val baseTo = to.plus(schedule.lead.maxSpan).plusNanos(1)
        val bases: List<Instant> = when (schedule) {
            is Schedule.At -> listOf(schedule.instant)
            is Schedule.DateOnly -> listOf(
                resolveLocal(schedule.date, schedule.time ?: defaultTimes[reminder.sourceType], zone),
            )
            is Schedule.Recurring -> schedule.rule.occurrencesBetween(from, baseTo, zone)
            is Schedule.Annual -> annualBases(reminder, schedule, from, baseTo, zone)
        }
        return bases
            .map { schedule.lead.applyTo(it, zone) }
            .filter { !it.isBefore(from) && it.isBefore(to) }
            .distinct()
            .sorted()
    }

    /** True if [reminder]'s schedule produces a ring exactly at [instant]. */
    fun firesAt(reminder: Reminder, instant: Instant): Boolean =
        fireTimes(reminder, instant, instant.plusNanos(1)).isNotEmpty()

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
