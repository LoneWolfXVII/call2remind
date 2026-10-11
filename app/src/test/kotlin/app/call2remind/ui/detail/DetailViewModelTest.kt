package app.call2remind.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.sources.habit.ReminderBackedHabitRepository
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.hours
import app.call2remind.testing.reminder
import app.call2remind.ui.FakeRingtoneCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {
    private val h = EngineHarness()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val habits = ReminderBackedHabitRepository(h.reminders, h.sources, h.engine, h.clock) { "walk" }
    private val events = mutableListOf<DetailEvent>()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        appScope.cancel()
        h.close()
    }

    private fun open(occurrence: Occurrence): DetailViewModel {
        val handle = SavedStateHandle(
            mapOf(DetailViewModel.ARG_OCCURRENCE to occurrence.id, DetailViewModel.ARG_REMINDER to occurrence.reminderId),
        )
        val vm = DetailViewModel(handle, h.reminders, h.occurrences, h.sources, h.settings, h.engine, habits, FakeRingtoneCatalog(), Dispatchers.IO, appScope)
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.state.collect { } }
        jobs += CoroutineScope(Dispatchers.Main).launch { vm.eventFlow.collect { events += it } }
        awaitUntil(message = "loaded") { vm.state.value.reminder != null && vm.state.value.occurrence != null }
        return vm
    }

    private fun calendarEvent(): Occurrence {
        val event = reminder("42:1773144000000", Schedule.At(T0.plus(hours(2))), sourceType = SourceType.CALENDAR, title = "Standup")
        runBlocking { h.engine.upsertReminders(listOf(event), "calendar") }
        return runBlocking { h.occurrences.getPending().single() }
    }

    @Test
    fun skipSkipsThePendingCallAndCloses() {
        val occurrence = calendarEvent()
        val vm = open(occurrence)
        assertThat(vm.state.value.canSkip).isTrue()
        assertThat(vm.state.value.isHabit).isFalse()

        vm.skip()

        awaitUntil(message = "skipped") { runBlocking { h.occurrences.get(occurrence.id) }?.state == OccurrenceState.SKIPPED }
        assertThat(events).contains(DetailEvent.Close)
    }

    @Test
    fun turningRingingOffPausesTheReminderAndOnBringsTheCallBack() {
        val occurrence = calendarEvent()
        val vm = open(occurrence)

        vm.setRinging(false)

        awaitUntil(message = "disabled") { vm.state.value.reminder?.enabled == false }
        awaitUntil(message = "cancelled") { runBlocking { h.occurrences.getPending() }.isEmpty() }

        vm.setRinging(true)

        awaitUntil(message = "replanned") { runBlocking { h.occurrences.getPending() }.map { it.id } == listOf(occurrence.id) }
        awaitUntil(message = "panel back") { vm.state.value.occurrence?.state == OccurrenceState.SCHEDULED }
        assertThat(events).isEmpty()
    }

    @Test
    fun aHabitCanBeDeleted() {
        runBlocking {
            habits.create("Walk", RecurrenceRule(setOf(DayOfWeek.TUESDAY), setOf(LocalTime.of(10, 0)), LocalDate.of(2026, 3, 1)))
        }
        val occurrence = runBlocking { h.occurrences.getPending().first() }
        val vm = open(occurrence)
        assertThat(vm.state.value.isHabit).isTrue()

        vm.deleteHabit()

        awaitUntil(message = "deleted") { runBlocking { habits.get("walk") } == null }
        assertThat(events).contains(DetailEvent.Close)
    }
}
