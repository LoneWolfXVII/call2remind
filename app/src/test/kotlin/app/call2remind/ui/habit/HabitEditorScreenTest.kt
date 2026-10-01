package app.call2remind.ui.habit

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import app.call2remind.sources.habit.ReminderBackedHabitRepository
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.awaitUntil
import app.call2remind.ui.FakeRingtoneCatalog
import app.call2remind.ui.components.SheetHostLayer
import app.call2remind.ui.components.SheetTags
import app.call2remind.ui.components.TimePickerTags
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HabitEditorScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val h = EngineHarness()
    private val habits = ReminderBackedHabitRepository(h.reminders, h.sources, h.engine, h.clock) { "h1" }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vm: HabitEditorViewModel

    @Before
    fun setUp() {
        vm = HabitEditorViewModel(SavedStateHandle(), habits, FakeRingtoneCatalog(), h.clock, Dispatchers.IO, appScope)
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SheetHostLayer {
                    val state by vm.state.collectAsState()
                    HabitEditorScreen(
                        state = state,
                        actions = HabitEditorActions(
                            onSave = vm::save,
                            onTitle = vm::setTitle,
                            onToggleDay = vm::toggleDay,
                            onAddTime = vm::addTime,
                            onReplaceTime = vm::replaceTime,
                            onRemoveTime = vm::removeTime,
                            onInterval = vm::setInterval,
                            onTts = vm::setTts,
                            onNotes = vm::setNotes,
                            onTemplate = vm::applyTemplate,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        appScope.cancel()
        h.close()
    }

    @Test
    fun savingWithoutANameShowsTheProblemAndStoresNothing() {
        rule.onNodeWithTag(HabitTags.SAVE).performClick()

        rule.onNodeWithTag(HabitTags.ERROR_TITLE).assertIsDisplayed()
        assertThat(runBlocking { habits.get("h1") }).isNull()
    }

    @Test
    fun typingANameAndSavingCreatesTheHabit() {
        rule.onNodeWithTag(HabitTags.TITLE).performTextInput("Stretch")
        rule.onNodeWithTag(HabitTags.SAVE).performClick()

        awaitUntil(message = "habit stored") { runBlocking { habits.get("h1") }?.title == "Stretch" }
        rule.onNodeWithTag(HabitTags.ERROR_TITLE).assertDoesNotExist()
    }

    @Test
    fun dayTogglesFlipAndAnEmptyWeekIsAnError() {
        val monday = rule.onNodeWithTag(HabitTags.day(DayOfWeek.MONDAY))
        monday.assertIsOn().performClick()
        monday.assertIsOff()
        assertThat(vm.state.value.form.days).doesNotContain(DayOfWeek.MONDAY)

        DayOfWeek.entries.filter { it != DayOfWeek.MONDAY }.forEach { rule.onNodeWithTag(HabitTags.day(it)).performClick() }
        rule.onNodeWithTag(HabitTags.SAVE).performClick()

        rule.onNodeWithTag(HabitTags.ERROR_DAYS).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun addingATimeGoesThroughThePickerSheet() {
        rule.onNodeWithTag(HabitTags.ADD_TIME).performScrollTo().performClick()
        rule.onNodeWithTag(SheetTags.SHEET).assertIsDisplayed()
        rule.onNodeWithTag(TimePickerTags.CONFIRM).performScrollTo().performClick()
        rule.waitForIdle()

        assertThat(vm.state.value.form.times).containsExactly(LocalTime.of(9, 0), LocalTime.of(10, 0)).inOrder()
        rule.onNodeWithTag(HabitTags.time(1)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTemplateFillsTheForm() {
        rule.onNodeWithTag(HabitTags.template(HabitTemplate.STANDUP)).performClick()

        assertThat(vm.state.value.form.days).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        rule.onNodeWithTag(HabitTags.day(DayOfWeek.SATURDAY)).assertIsOff()
    }
}
