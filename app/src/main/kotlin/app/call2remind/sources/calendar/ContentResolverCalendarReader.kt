package app.call2remind.sources.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** [CalendarReader] over the system calendar provider. Blocking: call off the main thread. */
@Singleton
class ContentResolverCalendarReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarReader {
    private val resolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarInfo(
                            id = c.getLong(0),
                            displayName = c.stringOrNull(1),
                            accountName = c.stringOrNull(2),
                            accountType = c.stringOrNull(3),
                            ownerAccount = c.stringOrNull(4),
                            visible = c.intOrNull(5)?.let { it != 0 } ?: true,
                            syncEvents = c.intOrNull(6)?.let { it != 0 } ?: true,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun instances(begin: Instant, end: Instant): List<CalendarInstance> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also {
                ContentUris.appendId(it, begin.toEpochMilli())
                ContentUris.appendId(it, end.toEpochMilli())
            }
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.EVENT_TIMEZONE,
        )
        return resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarInstance(
                            eventId = c.getLong(0),
                            calendarId = c.getLong(1),
                            begin = Instant.ofEpochMilli(c.getLong(2)),
                            end = c.longOrNull(3)?.let(Instant::ofEpochMilli),
                            title = c.stringOrNull(4),
                            allDay = c.intOrNull(5) == 1,
                            status = c.intOrNull(6),
                            selfAttendeeStatus = c.intOrNull(7),
                            eventTimezone = c.stringOrNull(8),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun reminders(eventIds: Collection<Long>): Map<Long, List<CalendarReminder>> {
        if (eventIds.isEmpty()) return emptyMap()
        val projection = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD,
        )
        val result = HashMap<Long, MutableList<CalendarReminder>>()
        for (chunk in eventIds.distinct().chunked(MAX_SQL_ARGS)) {
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN (${chunk.joinToString(",") { "?" }})"
            val args = chunk.map { it.toString() }.toTypedArray()
            resolver.query(CalendarContract.Reminders.CONTENT_URI, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val minutes = c.intOrNull(1) ?: continue
                    result.getOrPut(c.getLong(0)) { mutableListOf() } += CalendarReminder(minutes, c.intOrNull(2) ?: 0)
                }
            }
        }
        return result
    }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}

internal fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

internal fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

internal fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
