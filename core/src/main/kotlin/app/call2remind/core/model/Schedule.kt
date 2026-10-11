package app.call2remind.core.model

import app.call2remind.core.recurrence.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * When a [Reminder] rings. Every variant carries a [lead] offset that moves the ring earlier
 * than the scheduled moment.
 *
 * Wall-clock variants ([DateOnly], [Recurring], [Annual]) are resolved in [Reminder.zone] with
 * the rules of [app.call2remind.core.time.resolveLocal] (DST gap → shifted forward by the gap,
 * overlap → earlier offset).
 */
sealed interface Schedule {
    /** Offset subtracted from each scheduled moment to get the ring instant. */
    val lead: LeadOffset

    /** A single absolute moment, e.g. a timed calendar event start or an MS To Do reminder. */
    data class At(
        val instant: Instant,
        override val lead: LeadOffset = LeadOffset.NONE,
    ) : Schedule

    /**
     * A single date without a time (Google Tasks due date, all-day event, Samsung reminder).
     * Fires at [time] if given, otherwise at the source's [app.call2remind.core.time.DefaultTimes].
     */
    data class DateOnly(
        val date: LocalDate,
        val time: LocalTime? = null,
        override val lead: LeadOffset = LeadOffset.NONE,
    ) : Schedule

    /** A repeating habit. */
    data class Recurring(
        val rule: RecurrenceRule,
        override val lead: LeadOffset = LeadOffset.NONE,
    ) : Schedule

    /**
     * Once a year on [monthDay] (birthdays). February 29 fires on February 28 in non-leap years.
     * Fires at [time] if given, otherwise at the source's default time.
     *
     * @property sinceYear first year that produces an occurrence (typically the birth year);
     * `null` means every year.
     */
    data class Annual(
        val monthDay: MonthDay,
        val sinceYear: Int? = null,
        val time: LocalTime? = null,
        override val lead: LeadOffset = LeadOffset.NONE,
    ) : Schedule
}
