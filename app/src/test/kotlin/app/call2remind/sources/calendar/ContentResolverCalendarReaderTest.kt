package app.call2remind.sources.calendar

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** A calendar provider serving fixed rows, projected by column name. */
class FakeCalendarProvider : ContentProvider() {
    val queries = mutableListOf<Uri>()
    var calendars: List<Map<String, Any?>> = emptyList()
    var instances: List<Map<String, Any?>> = emptyList()
    var reminders: List<Map<String, Any?>> = emptyList()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries += uri
        val columns = projection ?: emptyArray()
        val rows = when (uri.pathSegments.firstOrNull()) {
            "calendars" -> calendars
            "instances" -> instances
            "reminders" -> reminders.filter { row -> selectionArgs == null || row[CalendarContract.Reminders.EVENT_ID].toString() in selectionArgs }
            else -> emptyList()
        }
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) } }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(RobolectricTestRunner::class)
class ContentResolverCalendarReaderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCalendarProvider
    private val reader by lazy { ContentResolverCalendarReader(app) }

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
    }

    @Test
    fun permissionIsCheckedAtRuntime() {
        assertThat(reader.hasPermission()).isFalse()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertThat(reader.hasPermission()).isTrue()
    }

    @Test
    fun readsCalendarsWithVisibilityAndSyncFlags() {
        provider.calendars = listOf(
            mapOf(
                CalendarContract.Calendars._ID to 3L,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "Work",
                CalendarContract.Calendars.ACCOUNT_NAME to "me@example.com",
                CalendarContract.Calendars.ACCOUNT_TYPE to "com.google",
                CalendarContract.Calendars.OWNER_ACCOUNT to "me@example.com",
                CalendarContract.Calendars.VISIBLE to 0,
                CalendarContract.Calendars.SYNC_EVENTS to 1,
            ),
            mapOf(CalendarContract.Calendars._ID to 4L),
        )

        assertThat(reader.calendars()).containsExactly(
            CalendarInfo(3, "Work", "me@example.com", "com.google", "me@example.com", visible = false, syncEvents = true),
            CalendarInfo(4, null, null, null, null, visible = true, syncEvents = true),
        ).inOrder()
    }

    @Test
    fun readsInstancesForTheRequestedWindow() {
        val begin = Instant.parse("2026-03-10T00:00:00Z")
        val end = Instant.parse("2026-03-17T00:00:00Z")
        provider.instances = listOf(
            mapOf(
                CalendarContract.Instances.EVENT_ID to 10L,
                CalendarContract.Instances.CALENDAR_ID to 3L,
                CalendarContract.Instances.BEGIN to Instant.parse("2026-03-11T09:00:00Z").toEpochMilli(),
                CalendarContract.Instances.END to Instant.parse("2026-03-11T10:00:00Z").toEpochMilli(),
                CalendarContract.Instances.TITLE to "Review",
                CalendarContract.Instances.ALL_DAY to 0,
                CalendarContract.Instances.STATUS to 1,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS to 2,
                CalendarContract.Instances.EVENT_TIMEZONE to "Europe/Berlin",
            ),
            mapOf(
                CalendarContract.Instances.EVENT_ID to 11L,
                CalendarContract.Instances.CALENDAR_ID to 3L,
                CalendarContract.Instances.BEGIN to Instant.parse("2026-03-12T00:00:00Z").toEpochMilli(),
                CalendarContract.Instances.ALL_DAY to 1,
            ),
        )

        val result = reader.instances(begin, end)

        val uri = provider.queries.single()
        assertThat(uri.pathSegments).containsExactly("instances", "when", begin.toEpochMilli().toString(), end.toEpochMilli().toString()).inOrder()
        assertThat(result).containsExactly(
            CalendarInstance(
                eventId = 10,
                calendarId = 3,
                begin = Instant.parse("2026-03-11T09:00:00Z"),
                end = Instant.parse("2026-03-11T10:00:00Z"),
                title = "Review",
                allDay = false,
                status = 1,
                selfAttendeeStatus = 2,
                eventTimezone = "Europe/Berlin",
            ),
            CalendarInstance(11, 3, Instant.parse("2026-03-12T00:00:00Z"), null, null, allDay = true),
        ).inOrder()
    }

    @Test
    fun readsRemindersGroupedByEvent() {
        provider.reminders = listOf(
            mapOf(CalendarContract.Reminders.EVENT_ID to 10L, CalendarContract.Reminders.MINUTES to 10, CalendarContract.Reminders.METHOD to 1),
            mapOf(CalendarContract.Reminders.EVENT_ID to 10L, CalendarContract.Reminders.MINUTES to 30, CalendarContract.Reminders.METHOD to 2),
            mapOf(CalendarContract.Reminders.EVENT_ID to 12L, CalendarContract.Reminders.MINUTES to 5, CalendarContract.Reminders.METHOD to 1),
            mapOf(CalendarContract.Reminders.EVENT_ID to 99L, CalendarContract.Reminders.MINUTES to 5, CalendarContract.Reminders.METHOD to 1),
        )

        val result = reader.reminders(listOf(10L, 12L, 10L))

        assertThat(result).containsExactly(
            10L, listOf(CalendarReminder(10, 1), CalendarReminder(30, 2)),
            12L, listOf(CalendarReminder(5, 1)),
        )
    }

    @Test
    fun noEventIdsMeansNoQuery() {
        assertThat(reader.reminders(emptyList())).isEmpty()
        assertThat(provider.queries).isEmpty()
    }
}
