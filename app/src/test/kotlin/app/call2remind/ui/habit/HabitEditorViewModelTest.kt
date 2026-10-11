package app.call2remind.ui.habit

import androidx.lifecycle.SavedStateHandle
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.sources.habit.ReminderBackedHabitRepository
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.awaitUntil
import app.call2remind.ui.FakeRingtoneCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class HabitEditorViewModelTest {
    private val h = EngineHarness()
    private var ids = 0
    private val habits = ReminderBackedHabitRepository(h.reminders, h.sources, h.engine, h.clock) { "h${++ids}" }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = mutableListOf<HabitEditorEvent>()

    @After
    fun tearDown() {
        appScope.cancel()
        h.close()
    }

    private fun editor(
        habitId: String? = null,
        handle: SavedStateHandle = SavedStateHandle(if (habitId == null) emptyMap() else mapOf(HabitEditorViewModel.ARG_HABIT_ID to habitId)),
    ): HabitEditorViewModel {
        val vm = HabitEditorViewModel(handle, habits, FakeRingtoneCatalog(), h.clock, Dispatchers.IO, appScope)
        CoroutineScope(Dispatchers.Main).launch { vm.eventFlow.collect { events += it } }
        return vm
    }

    @Test
    fun aNewHabitStartsDailyAtTheNextHourWithoutShowingErrors() {
        val vm = editor()
        val state = vm.state.value

        assertThat(state.isNew).isTrue()
        assertThat(state.form.days).containsExactlyElementsIn(DayOfWeek.entries)
        // T0 is 08:00 UTC.
        assertThat(state.form.times).containsExactly(LocalTime.of(9, 0))
        assertThat(state.errors).containsExactly(HabitError.TITLE)
        assertThat(state.visibleErrors).isEmpty()
    }

    @Test
    fun savingAnInvalidFormShowsEveryProblemAndStoresNothing() {
        val vm = editor()
        DayOfWeek.entries.forEach(vm::toggleDay)
        vm.removeTime(LocalTime.of(9, 0))

        vm.save()

        assertThat(vm.state.value.visibleErrors).containsExactly(HabitError.TITLE, HabitError.DAYS, HabitError.TIMES)
        assertThat(runBlocking { habits.get("h1") }).isNull()
        assertThat(events).isEmpty()
    }

    @Test
    fun savingAValidFormCreatesTheHabitAndPlansIt() {
        val vm = editor()
        vm.setTitle("Take vitamin D")
        vm.toggleDay(DayOfWeek.SATURDAY)
        vm.toggleDay(DayOfWeek.SUNDAY)
        vm.addTime(LocalTime.of(21, 0))
        vm.setInterval(2)
        vm.setRingtone("content://tone")
        vm.setNotes("  With food ")

        vm.save()

        awaitUntil(message = "saved") { events.contains(HabitEditorEvent.Saved) }
        val habit = runBlocking { habits.get("h1") }!!
        assertThat(habit.title).isEqualTo("Take vitamin D")
        assertThat(habit.rule.daysOfWeek).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertThat(habit.rule.times).containsExactly(LocalTime.of(9, 0), LocalTime.of(21, 0))
        assertThat(habit.rule.intervalWeeks).isEqualTo(2)
        assertThat(habit.rule.startDate).isEqualTo(LocalDate.of(2026, 3, 10))
        assertThat(habit.ringtoneUri).isEqualTo("content://tone")
        assertThat(habit.notes).isEqualTo("With food")
        assertThat(runBlocking { h.occurrences.getPending() }).isNotEmpty()
    }

    @Test
    fun timesStaySortedAndUnique() {
        val vm = editor()
        vm.addTime(LocalTime.of(7, 30))
        vm.addTime(LocalTime.of(9, 0))
        vm.replaceTime(LocalTime.of(9, 0), LocalTime.of(6, 0))

        assertThat(vm.state.value.form.times).containsExactly(LocalTime.of(6, 0), LocalTime.of(7, 30)).inOrder()
    }

    @Test
    fun aTemplateFillsTitleDaysAndTimes() {
        val vm = editor()

        vm.applyTemplate(HabitTemplate.GYM, "Gym")

        val form = vm.state.value.form
        assertThat(form.title).isEqualTo("Gym")
        assertThat(form.days).containsExactly(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
        assertThat(form.times).containsExactly(LocalTime.of(18, 30))
        assertThat(vm.state.value.template).isEqualTo(HabitTemplate.GYM)
    }

    @Test
    fun editingKeepsIdAndStartDateAndDeleteRemovesIt() {
        val start = LocalDate.of(2026, 2, 2)
        val existing = runBlocking {
            habits.create("Walk", RecurrenceRule(setOf(DayOfWeek.MONDAY), setOf(LocalTime.of(7, 0)), start, intervalWeeks = 2))
        }
        val vm = editor(existing.id)
        awaitUntil(message = "loaded") { !vm.state.value.loading }
        assertThat(vm.state.value.isNew).isFalse()
        assertThat(vm.state.value.form.title).isEqualTo("Walk")

        vm.setTitle("Long walk")
        vm.save()

        awaitUntil(message = "saved") { events.contains(HabitEditorEvent.Saved) }
        val updated = runBlocking { habits.get(existing.id) }!!
        assertThat(updated.title).isEqualTo("Long walk")
        assertThat(updated.rule.startDate).isEqualTo(start)
        assertThat(updated.rule.intervalWeeks).isEqualTo(2)

        vm.delete()
        awaitUntil(message = "deleted") { events.contains(HabitEditorEvent.Deleted) }
        assertThat(runBlocking { habits.get(existing.id) }).isNull()
    }

    @Test
    fun anUnsavedNewFormSurvivesProcessDeath() {
        val handle = SavedStateHandle()
        val first = editor(handle = handle)
        first.applyTemplate(HabitTemplate.GYM, "Gym")
        first.setTitle("Leg day")
        first.setInterval(2)
        first.setNotes("Bring shoes")

        val restored = editor(handle = handle).state.value

        assertThat(restored.form).isEqualTo(first.state.value.form)
        assertThat(restored.template).isEqualTo(HabitTemplate.GYM)
        assertThat(restored.form.times).containsExactly(LocalTime.of(18, 30))
    }

    @Test
    fun aRestoredEditKeepsTheUnsavedChangesOverTheStoredHabit() {
        val existing = runBlocking {
            habits.create("Walk", RecurrenceRule(setOf(DayOfWeek.MONDAY), setOf(LocalTime.of(7, 0)), LocalDate.of(2026, 2, 2)))
        }
        val handle = SavedStateHandle(mapOf(HabitEditorViewModel.ARG_HABIT_ID to existing.id))
        val first = editor(existing.id, handle)
        awaitUntil(message = "loaded") { !first.state.value.loading }
        first.setTitle("Long walk")

        val second = editor(existing.id, handle)
        awaitUntil(message = "reloaded") { !second.state.value.loading }

        assertThat(second.state.value.isNew).isFalse()
        assertThat(second.state.value.form.title).isEqualTo("Long walk")
    }

    @Test
    fun syncedTextIgnoresItsOwnStaleEchoesButTakesExternalChanges() {
        val sent = mutableListOf<String>()
        val text = SyncedText("", maxLength = 5) { sent += it }

        text.input("a")
        text.input("ab")
        text.input("abc")
        text.external("a") // a stale echo of our own keystroke
        assertThat(text.text).isEqualTo("abc")
        text.external("abc")
        text.external("Gym") // a template
        assertThat(text.text).isEqualTo("Gym")
        text.input("Gym day!")

        assertThat(text.text).isEqualTo("Gym d")
        assertThat(sent).containsExactly("a", "ab", "abc", "Gym d").inOrder()
    }

    @Test
    fun validationIsPure() {
        assertThat(HabitValidation.errors(HabitForm(title = " ", days = emptySet(), times = emptyList())))
            .containsExactly(HabitError.TITLE, HabitError.DAYS, HabitError.TIMES)
        assertThat(HabitValidation.errors(HabitForm(title = "x", days = setOf(DayOfWeek.MONDAY), times = listOf(LocalTime.NOON)))).isEmpty()
    }
}
