package app.call2remind.data.repo

import app.cash.turbine.test
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceEnd
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.mapper.toEntity
import app.call2remind.data.model.ReminderSource
import app.call2remind.testing.MutableClock
import app.call2remind.testing.T0
import app.call2remind.testing.awaitItemMatching
import app.call2remind.testing.hours
import app.call2remind.testing.newTestDb
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ReminderRepositoryTest {
    private val clock = MutableClock()
    private val db = newTestDb()
    private val reminders = RoomReminderRepository(db, clock)
    private val sources = RoomSourceRepository(db)

    @After
    fun tearDown() = db.close()

    @Test
    fun everyScheduleVariantRoundTripsThroughTheDatabase() = runBlocking<Unit> {
        val schedules = listOf(
            Schedule.At(T0.plus(hours(2)).plusNanos(123_456_789), LeadOffset(days = 1, duration = hours(1))),
            Schedule.DateOnly(LocalDate.of(2026, 3, 12)),
            Schedule.DateOnly(LocalDate.of(2026, 3, 12), LocalTime.of(6, 45), LeadOffset.minutes(30)),
            Schedule.Recurring(
                RecurrenceRule(
                    daysOfWeek = setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY),
                    times = setOf(LocalTime.of(7, 0), LocalTime.of(19, 30)),
                    startDate = LocalDate.of(2026, 3, 1),
                    end = RecurrenceEnd.Until(LocalDate.of(2026, 6, 30)),
                ),
            ),
            Schedule.Recurring(
                RecurrenceRule(setOf(DayOfWeek.SUNDAY), setOf(LocalTime.NOON), LocalDate.of(2026, 1, 4), 3, RecurrenceEnd.Count(5)),
                LeadOffset.minutes(5),
            ),
            Schedule.Annual(MonthDay.of(2, 29)),
            Schedule.Annual(MonthDay.of(12, 31), sinceYear = 1980, time = LocalTime.of(20, 0), lead = LeadOffset.days(1)),
        )
        val originals = schedules.mapIndexed { i, schedule ->
            reminder("s$i", schedule, notes = "n$i", zone = ZoneId.of("America/New_York"), ringtoneUri = "tone$i")
        }

        reminders.upsertAll(originals, sourceId = "src")

        for (original in originals) {
            assertThat(reminders.get(original.id)).isEqualTo(original)
        }
        assertThat(reminders.getAll()).containsExactlyElementsIn(originals)
    }

    @Test
    fun upsertKeepsTheStoredIdForTheSameSourceIdentity() = runBlocking<Unit> {
        val stored = reminders.upsert(reminder("ext", title = "Old"))
        val incoming = reminder("ext", title = "New").copy(id = "some-other-local-id")

        val result = reminders.upsert(incoming)

        assertThat(result.id).isEqualTo(stored.id)
        assertThat(result.title).isEqualTo("New")
        assertThat(reminders.getAll().map { it.id }).containsExactly(stored.id)
        assertThat(reminders.get(stored.id)?.title).isEqualTo("New")
    }

    @Test
    fun upsertAllDeduplicatesBySourceIdentityLastWins() = runBlocking<Unit> {
        val result = reminders.upsertAll(listOf(reminder("dup", title = "first"), reminder("dup", title = "second")))

        assertThat(result.map { it.title }).containsExactly("second")
        assertThat(reminders.getAll().single().title).isEqualTo("second")
    }

    @Test
    fun sameExternalIdInDifferentSourcesAreDistinct() = runBlocking<Unit> {
        reminders.upsertAll(
            listOf(reminder("42", sourceType = SourceType.CALENDAR), reminder("42", sourceType = SourceType.GOOGLE_TASKS)),
        )
        assertThat(reminders.getAll()).hasSize(2)
    }

    @Test
    fun replaceForSourceDeletesOnlyThatSourcesStaleReminders() = runBlocking<Unit> {
        reminders.upsertAll(listOf(reminder("a"), reminder("b")), sourceId = "s1")
        reminders.upsertAll(listOf(reminder("c")), sourceId = "s2")

        val result = reminders.replaceForSource("s1", listOf(reminder("b", title = "B2"), reminder("d")))

        assertThat(result.deletedIds).containsExactly(reminder("a").id)
        assertThat(result.upserted.map { it.externalId }).containsExactly("b", "d")
        assertThat(reminders.getAll().map { it.externalId }).containsExactly("b", "c", "d")
        assertThat(reminders.get(reminder("b").id)?.title).isEqualTo("B2")
    }

    @Test
    fun deleteRemovesRowsAndReportsCount() = runBlocking<Unit> {
        reminders.upsertAll(listOf(reminder("a"), reminder("b")))
        assertThat(reminders.delete(listOf(reminder("a").id, "missing"))).isEqualTo(1)
        assertThat(reminders.getAll().map { it.externalId }).containsExactly("b")
    }

    @Test
    fun observersSkipCorruptRowsAndSortByTitle() = runBlocking<Unit> {
        reminders.upsertAll(listOf(reminder("z", title = "zeta"), reminder("a", title = "Alpha")))
        db.reminderDao().upsertAll(listOf(reminder("bad", title = "beta").toEntity(null, T0).copy(scheduleJson = "{")))

        reminders.observeAll().test {
            assertThat(awaitItem().map { it.title }).containsExactly("Alpha", "zeta").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(reminders.getAll().map { it.title }).containsExactly("Alpha", "zeta")
        assertThat(reminders.get(reminder("bad").id)).isNull()
    }

    @Test
    fun observeSingleReminderFollowsChanges() = runBlocking<Unit> {
        val r = reminder("a")
        reminders.observe(r.id).test {
            assertThat(awaitItem()).isNull()
            reminders.upsert(r)
            assertThat(awaitItemMatching { it != null }).isEqualTo(r)
            reminders.delete(listOf(r.id))
            awaitItemMatching { it == null }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sourcesRoundTripSyncAndCascadeDelete() = runBlocking<Unit> {
        val source = ReminderSource(id = "cal:work", type = SourceType.CALENDAR, account = "me@work", displayName = "Work")
        sources.upsert(source)
        sources.upsert(ReminderSource(id = "habits", type = SourceType.HABIT))
        reminders.upsertAll(listOf(reminder("e1", sourceType = SourceType.CALENDAR)), sourceId = "cal:work")
        reminders.upsertAll(listOf(reminder("h1")), sourceId = "habits")

        assertThat(sources.get("cal:work")).isEqualTo(source)
        sources.markSynced("cal:work", T0, "cursor-1")
        assertThat(sources.get("cal:work")?.lastSyncAt).isEqualTo(T0)
        assertThat(sources.get("cal:work")?.syncCursor).isEqualTo("cursor-1")
        sources.observeAll().test {
            assertThat(awaitItem().map { it.id }).containsExactly("cal:work", "habits").inOrder()
            cancelAndIgnoreRemainingEvents()
        }

        sources.delete("cal:work")

        assertThat(sources.get("cal:work")).isNull()
        assertThat(sources.getAll().map { it.id }).containsExactly("habits")
        assertThat(reminders.getAll().map { it.externalId }).containsExactly("h1")
    }

    @Test
    fun setZoneMovesOnlyTheZoneOfTheGivenReminders() = runBlocking<Unit> {
        val la = ZoneId.of("America/Los_Angeles")
        val moved = reminders.upsert(reminder("a", sourceType = SourceType.CALENDAR), sourceId = "cal:work")
        val untouched = reminders.upsert(reminder("b", sourceType = SourceType.CALENDAR), sourceId = "cal:work")

        assertThat(reminders.setZone(listOf(moved.id), la)).isEqualTo(1)

        assertThat(reminders.get(moved.id)).isEqualTo(moved.copy(zone = la))
        assertThat(reminders.get(untouched.id)).isEqualTo(untouched)
        // Still attributed to its source: deleting the source deletes it.
        sources.delete("cal:work")
        assertThat(reminders.getAll()).isEmpty()
        assertThat(reminders.setZone(emptyList(), la)).isEqualTo(0)
    }
}
