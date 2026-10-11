package app.call2remind.sources.calendar

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.settings.SourceSettings
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Maps calendar instances to reminders. Pure; see [CalendarSource] for the provider reads.
 *
 * - One reminder per instance, `externalId = "<eventId>:<instanceBeginMillis>"`, so every
 *   occurrence of a recurring event (already expanded by `Instances`) is its own reminder.
 * - Timed events → [Schedule.At] at the instance start, with the event's **smallest** alert
 *   reminder (`METHOD_DEFAULT` / `ALERT` / `ALARM`, minutes >= 0) as the lead; none → rings at start.
 * - All-day events → [Schedule.DateOnly] on the event's date, ringing at the calendar default time.
 *   All-day instants are UTC midnight, so the date is read in UTC (never shifted into the local
 *   zone, which would move it a day back west of Greenwich). All-day reminder offsets are ignored.
 * - Skipped: cancelled events, events the user declined, calendars that are hidden, not synced,
 *   unknown, excluded in settings, or (while the Birthdays source is on) Google's contact
 *   birthdays calendar.
 */
object CalendarMapper {
    const val UNTITLED: String = "Untitled event"

    private val ALERT_METHODS = setOf(
        CalendarConstants.METHOD_DEFAULT,
        CalendarConstants.METHOD_ALERT,
        CalendarConstants.METHOD_ALARM,
    )

    fun map(
        instances: List<CalendarInstance>,
        calendars: List<CalendarInfo>,
        reminders: Map<Long, List<CalendarReminder>>,
        settings: SourceSettings,
        zone: ZoneId,
    ): List<Reminder> {
        val included = calendars.filter { isIncluded(it, settings) }.mapTo(HashSet()) { it.id }
        return instances
            .asSequence()
            .filter { it.calendarId in included }
            .filterNot { it.status == CalendarConstants.STATUS_CANCELED }
            .filterNot { it.selfAttendeeStatus == CalendarConstants.ATTENDEE_STATUS_DECLINED }
            .map { toReminder(it, reminders[it.eventId].orEmpty(), zone) }
            .distinctBy { it.externalId }
            .toList()
    }

    fun isIncluded(calendar: CalendarInfo, settings: SourceSettings): Boolean = when {
        !calendar.visible || !calendar.syncEvents -> false
        calendar.id in settings.excludedCalendarIds -> false
        calendar.isContactBirthdaysCalendar && settings.isEnabled(SourceType.BIRTHDAY) -> false
        else -> true
    }

    fun externalId(instance: CalendarInstance): String = "${instance.eventId}:${instance.begin.toEpochMilli()}"

    /** Smallest non-negative alert offset, or [LeadOffset.NONE]. */
    fun leadFor(reminders: List<CalendarReminder>): LeadOffset {
        val minutes = reminders
            .filter { it.method in ALERT_METHODS && it.minutes >= 0 }
            .minOfOrNull { it.minutes }
            ?: return LeadOffset.NONE
        return if (minutes == 0) LeadOffset.NONE else LeadOffset.minutes(minutes.toLong())
    }

    private fun toReminder(instance: CalendarInstance, reminders: List<CalendarReminder>, zone: ZoneId): Reminder {
        val externalId = externalId(instance)
        val schedule = if (instance.allDay) {
            Schedule.DateOnly(instance.begin.atZone(ZoneOffset.UTC).toLocalDate())
        } else {
            Schedule.At(instance.begin, leadFor(reminders))
        }
        return Reminder(
            id = ReminderIds.of(SourceType.CALENDAR, externalId),
            sourceType = SourceType.CALENDAR,
            externalId = externalId,
            title = instance.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            schedule = schedule,
            zone = zone,
        )
    }
}
