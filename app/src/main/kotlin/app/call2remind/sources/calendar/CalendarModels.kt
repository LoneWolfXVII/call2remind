package app.call2remind.sources.calendar

import java.time.Instant

/** A row of `CalendarContract.Calendars` (the parts sync needs). */
data class CalendarInfo(
    val id: Long,
    val displayName: String?,
    val accountName: String?,
    val accountType: String?,
    val ownerAccount: String? = null,
    /** `Calendars.VISIBLE`: the user shows this calendar in their calendar app. */
    val visible: Boolean = true,
    /** `Calendars.SYNC_EVENTS`: the calendar's events are synced to the device. */
    val syncEvents: Boolean = true,
) {
    /**
     * Google's generated "Birthdays" / "Contacts' birthdays" calendar. Skipped while the Birthdays
     * source is on, so a birthday doesn't ring twice (once as an all-day event, once from contacts).
     */
    val isContactBirthdaysCalendar: Boolean
        get() = listOfNotNull(ownerAccount, accountName).any { it.contains(CONTACT_BIRTHDAYS_MARKER, ignoreCase = true) }

    companion object {
        const val CONTACT_BIRTHDAYS_MARKER: String = "#contacts@group.v.calendar.google.com"
    }
}

/** One expanded row of `CalendarContract.Instances`. */
data class CalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    /** `Instances.BEGIN`. For all-day events this is UTC midnight of the event's date. */
    val begin: Instant,
    val end: Instant?,
    val title: String?,
    val allDay: Boolean,
    /** `Events.STATUS` (tentative / confirmed / canceled). */
    val status: Int? = null,
    /** `Events.SELF_ATTENDEE_STATUS` (none / accepted / declined / invited / tentative). */
    val selfAttendeeStatus: Int? = null,
    /** `Events.EVENT_TIMEZONE`, e.g. `Europe/Berlin`. */
    val eventTimezone: String? = null,
)

/** One row of `CalendarContract.Reminders`. */
data class CalendarReminder(val minutes: Int, val method: Int)

/** `CalendarContract` constants mirrored so the mapping stays pure Kotlin (values are API-stable). */
object CalendarConstants {
    /** `Events.STATUS_CANCELED`. */
    const val STATUS_CANCELED: Int = 2

    /** `Attendees.ATTENDEE_STATUS_DECLINED`. */
    const val ATTENDEE_STATUS_DECLINED: Int = 2

    /** `Reminders.METHOD_DEFAULT`, `METHOD_ALERT`, `METHOD_EMAIL`, `METHOD_SMS`, `METHOD_ALARM`. */
    const val METHOD_DEFAULT: Int = 0
    const val METHOD_ALERT: Int = 1
    const val METHOD_EMAIL: Int = 2
    const val METHOD_SMS: Int = 3
    const val METHOD_ALARM: Int = 4
}

/**
 * Read access to the calendar provider. The real implementation is
 * [ContentResolverCalendarReader]; tests use an in-memory fake.
 */
interface CalendarReader {
    /** True if `READ_CALENDAR` is granted. */
    fun hasPermission(): Boolean

    fun calendars(): List<CalendarInfo>

    /** Instances overlapping [[begin], [end]). */
    fun instances(begin: Instant, end: Instant): List<CalendarInstance>

    /** Reminders of [eventIds], by event id. */
    fun reminders(eventIds: Collection<Long>): Map<Long, List<CalendarReminder>>
}
