package app.call2remind.sources.calendar

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.settings.SourceSettings
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FakeCalendarReader(
    var permission: Boolean = true,
    var calendars: List<CalendarInfo> = listOf(CalendarInfo(1, "Work", "me", "com.google")),
    var instances: List<CalendarInstance> = emptyList(),
    var reminders: Map<Long, List<CalendarReminder>> = emptyMap(),
    var failWith: RuntimeException? = null,
) : CalendarReader {
    val instanceQueries = mutableListOf<Pair<Instant, Instant>>()
    val reminderQueries = mutableListOf<Set<Long>>()

    override fun hasPermission(): Boolean = permission

    override fun calendars(): List<CalendarInfo> = calendars

    override fun instances(begin: Instant, end: Instant): List<CalendarInstance> {
        failWith?.let { throw it }
        instanceQueries += begin to end
        return instances
    }

    override fun reminders(eventIds: Collection<Long>): Map<Long, List<CalendarReminder>> {
        reminderQueries += eventIds.toSet()
        return reminders.filterKeys { it in eventIds }
    }
}

class CalendarSourceTest {
    private val reader = FakeCalendarReader()
    private val source = CalendarSource(reader, Dispatchers.Unconfined)
    private val request = SyncRequest(now = T0, zone = UTC, cursor = null, settings = SourceSettings())

    private fun timed(eventId: Long, begin: Instant) = CalendarInstance(eventId, 1, begin, null, "E$eventId", allDay = false)

    @Test
    fun isLocalCalendarSource() {
        assertThat(source.requiresNetwork).isFalse()
        assertThat(source.sourceId).isEqualTo("calendar")
    }

    @Test
    fun availabilityFollowsThePermission() = runBlocking<Unit> {
        assertThat(source.availability()).isEqualTo(SourceAvailability.Ready)
        reader.permission = false
        assertThat(source.availability()).isEqualTo(SourceAvailability.NeedsPermission("android.permission.READ_CALENDAR"))
    }

    @Test
    fun readsTheConfiguredWindowWithADayOfMarginOnEachSide() = runBlocking<Unit> {
        source.snapshot(request.copy(settings = SourceSettings(calendarDaysAhead = 3)))

        assertThat(reader.instanceQueries.single())
            .isEqualTo(T0.minus(Duration.ofDays(1)) to T0.plus(Duration.ofDays(4)))
    }

    @Test
    fun defaultWindowIsSevenDays() = runBlocking<Unit> {
        source.snapshot(request)

        assertThat(reader.instanceQueries.single().second).isEqualTo(T0.plus(Duration.ofDays(8)))
    }

    @Test
    fun fullSnapshotJoinsRemindersOfTimedEventsOnly() = runBlocking<Unit> {
        reader.instances = listOf(
            timed(1, T0.plus(Duration.ofHours(2))),
            CalendarInstance(2, 1, Instant.parse("2026-03-12T00:00:00Z"), null, "Holiday", allDay = true),
        )
        reader.reminders = mapOf(1L to listOf(CalendarReminder(5, CalendarConstants.METHOD_ALERT)))

        val snapshot = source.snapshot(request)

        assertThat(snapshot).isInstanceOf(SourceSnapshot.Full::class.java)
        assertThat(snapshot.cursor).isNull()
        assertThat(reader.reminderQueries.single()).containsExactly(1L)
        val byTitle = (snapshot as SourceSnapshot.Full).reminders.associateBy { it.title }
        assertThat(byTitle.getValue("E1").schedule).isEqualTo(Schedule.At(T0.plus(Duration.ofHours(2)), LeadOffset.minutes(5)))
        assertThat(byTitle.getValue("Holiday").schedule).isInstanceOf(Schedule.DateOnly::class.java)
    }

    @Test
    fun noInstancesSkipsTheRemindersQuery() = runBlocking<Unit> {
        val snapshot = source.snapshot(request) as SourceSnapshot.Full

        assertThat(snapshot.reminders).isEmpty()
        assertThat(reader.reminderQueries).isEmpty()
    }

    @Test
    fun missingPermissionThrowsNeedsPermission() {
        reader.permission = false

        val e = assertThrows(NeedsPermissionException::class.java) { runBlocking { source.snapshot(request) } }
        assertThat(e.permission).isEqualTo(CalendarSource.READ_CALENDAR)
    }

    @Test
    fun securityExceptionFromTheProviderMeansPermissionRevoked() {
        reader.failWith = SecurityException("revoked")

        assertThrows(NeedsPermissionException::class.java) { runBlocking { source.snapshot(request) } }
    }
}
