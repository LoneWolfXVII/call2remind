package app.call2remind.e2e.support

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A local (ACCOUNT_TYPE_LOCAL) calendar in the system calendar provider.
 *
 * Instrumentation code runs in the app's process with the app's permissions, and the app only
 * holds READ_CALENDAR. Writes therefore adopt the shell's permission identity (the shell holds
 * WRITE_CALENDAR), for the duration of each write only.
 */
object LocalCalendar {
    const val ACCOUNT = "c2r-e2e@local"

    private val resolver get() = Device.context.contentResolver

    private fun <T> asShell(block: () -> T): T {
        val ua = Device.instrumentation.uiAutomation
        ua.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
        try {
            return block()
        } finally {
            ua.dropShellPermissionIdentity()
        }
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** Creates a visible, synced local calendar and returns its id. */
    fun create(): Long = asShell {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Call2Remind E2E")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Call2Remind E2E")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }
        val uri = requireNotNull(resolver.insert(CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(), values)) {
            "calendar insert returned null"
        }
        ContentUris.parseId(uri).also { e2eLog("created local calendar $it") }
    }

    /** A timed event starting at [start] with one alert [reminderMinutes] before. Returns the event id. */
    fun insertTimedEvent(calendarId: Long, title: String, start: Instant, durationMinutes: Long, reminderMinutes: Int): Long = asShell {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.toEpochMilli())
            put(CalendarContract.Events.DTEND, start.plusSeconds(durationMinutes * 60).toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        val eventId = ContentUris.parseId(requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)))
        val reminder = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        requireNotNull(resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder))
        e2eLog("inserted timed event $eventId '$title' at $start, alert ${reminderMinutes}m before")
        eventId
    }

    /** An all-day event on [date] (UTC midnight to midnight, as the provider stores all-day events). */
    fun insertAllDayEvent(calendarId: Long, title: String, date: LocalDate): Long = asShell {
        val begin = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin.toEpochMilli())
            put(CalendarContract.Events.DTEND, begin.plusSeconds(86_400).toEpochMilli())
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        ContentUris.parseId(requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, values)))
            .also { e2eLog("inserted all-day event $it '$title' on $date") }
    }

    /** Deletes every calendar (and so every event) of the test account. */
    fun deleteAll() {
        runCatching {
            asShell {
                resolver.delete(
                    CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(),
                    "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                    arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL),
                )
            }
        }.onFailure { e2eLog("calendar cleanup failed: $it") }
    }
}
