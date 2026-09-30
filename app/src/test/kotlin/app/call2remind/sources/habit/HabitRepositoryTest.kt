package app.call2remind.sources.habit

import app.cash.turbine.test
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.model.ReminderIds
import app.call2remind.sources.SourceIds
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.awaitItemMatching
import app.call2remind.testing.hours
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

@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {
    private val h = EngineHarness()
    private var ids = 0
    private val repo = ReminderBackedHabitRepository(h.reminders, h.sources, h.engine, h.clock) { "h${++ids}" }

    // T0 is Tuesday 2026-03-10 08:00 UTC.
    private val daily9 = RecurrenceRule(DayOfWeek.entries.toSet(), setOf(LocalTime.of(9, 0)), LocalDate.of(2026, 3, 1))

    @After
    fun tearDown() = h.close()

    @Test
    fun createStoresAHabitReminderAndPlansIt() = runBlocking<Unit> {
        val habit = repo.create("  Drink water ", daily9, notes = " 500 ml ", lead = LeadOffset.minutes(5))

        assertThat(habit).isEqualTo(
            Habit(id = "h1", title = "Drink water", rule = daily9, zone = UTC, notes = "500 ml", lead = LeadOffset.minutes(5)),
        )
        val stored = h.reminders.get(ReminderIds.of(SourceType.HABIT, "h1"))
        assertThat(stored?.schedule).isEqualTo(Schedule.Recurring(daily9, LeadOffset.minutes(5)))
        assertThat(h.occurrences.getPending().map { it.fireAt })
            .containsExactly(T0.plus(hours(1)).minusSeconds(300), T0.plus(hours(25)).minusSeconds(300)).inOrder()
        assertThat(h.sources.get(SourceIds.HABITS)?.type).isEqualTo(SourceType.HABIT)
        assertThat(repo.get("h1")).isEqualTo(habit)
    }

    @Test
    fun updateReplansWithTheNewRule() = runBlocking<Unit> {
        val habit = repo.create("Walk", daily9)

        repo.update(habit.copy(rule = daily9.copy(times = setOf(LocalTime.of(18, 0)))))

        assertThat(h.occurrences.getPending().map { it.fireAt })
            .containsExactly(T0.plus(hours(10)), T0.plus(hours(34))).inOrder()
        assertThat(repo.get(habit.id)?.rule?.times).containsExactly(LocalTime.of(18, 0))
    }

    @Test
    fun pausingCancelsPendingRingsAndResumingRestoresThem() = runBlocking<Unit> {
        val habit = repo.create("Stretch", daily9)

        assertThat(repo.setEnabled(habit.id, false)).isTrue()
        assertThat(h.occurrences.getPending()).isEmpty()
        assertThat(repo.get(habit.id)?.enabled).isFalse()

        repo.setEnabled(habit.id, true)
        assertThat(h.occurrences.getPending()).hasSize(2)
        assertThat(repo.setEnabled("missing", true)).isFalse()
    }

    @Test
    fun deleteRemovesTheReminderAndItsPendingRings() = runBlocking<Unit> {
        val habit = repo.create("Read", daily9)

        repo.delete(habit.id)

        assertThat(repo.get(habit.id)).isNull()
        assertThat(h.occurrences.getPending()).isEmpty()
        assertThat(h.alarms.armed).isEmpty()
    }

    @Test
    fun observeAllListsOnlyHabits() = runBlocking<Unit> {
        h.reminders.upsert(reminder("task", Schedule.At(T0.plus(hours(2))), sourceType = SourceType.GOOGLE_TASKS))

        repo.observeAll().test {
            awaitItemMatching { it.isEmpty() }
            repo.create("Meditate", daily9)
            assertThat(awaitItemMatching { it.isNotEmpty() }.map { it.title }).containsExactly("Meditate")
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(h.occurrences.getPending().map { it.state }.toSet()).containsExactly(OccurrenceState.SCHEDULED)
    }

    @Test
    fun fromReminderRejectsNonHabits() {
        assertThat(Habit.fromReminder(reminder("x", Schedule.At(T0), sourceType = SourceType.CALENDAR))).isNull()
        assertThat(Habit.fromReminder(reminder("y", Schedule.At(T0), sourceType = SourceType.HABIT))).isNull()
        val habit = Habit("z", "Z", daily9, UTC, enabled = false, ringtoneUri = "content://r", ttsEnabled = false)
        assertThat(Habit.fromReminder(habit.toReminder())).isEqualTo(habit)
    }
}
