package app.call2remind.ui.history

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HistoryScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val missed = HistoryItem("m1", "Drink water", SourceType.HABIT, T0.minus(hours(1)), T0.minus(hours(1)), OccurrenceState.MISSED, ringBacks = 2)
    private val done = HistoryItem("d1", "Standup", SourceType.CALENDAR, T0.minus(hours(2)), T0.minus(hours(2)), OccurrenceState.DONE, ringBacks = 1)
    private val rang = mutableListOf<String>()
    private val doneIds = mutableListOf<String>()
    private var logToggles = 0

    private fun show(state: HistoryUiState) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                HistoryScreen(
                    state = state,
                    now = T0,
                    zone = UTC,
                    actions = HistoryActions(
                        onToggleLog = { logToggles++ },
                        onRingAgain = { rang += it },
                        onMarkDone = { doneIds += it },
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private val model = HistoryModel(missed = listOf(missed), days = listOf(HistoryDay(LocalDate.of(2026, 3, 10), listOf(done))))

    @Test
    fun missedCallsCanBeRungAgainOrMarkedDone() {
        show(HistoryUiState(loading = false, model = model))

        rule.onNodeWithText("Rang 3 times from", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.ringAgain("m1")).performClick()
        rule.onNodeWithTag(HistoryTags.done("m1")).performClick()

        assertThat(rang).containsExactly("m1")
        assertThat(doneIds).containsExactly("m1")
        rule.onNodeWithText(context.getString(R.string.history_outcome_snoozed_done)).assertIsDisplayed()
    }

    @Test
    fun longPressOnTheTitleOpensTheRingLog() {
        show(HistoryUiState(loading = false, model = model))

        rule.onNodeWithTag(HistoryTags.TITLE).performTouchInput { longClick() }

        assertThat(logToggles).isEqualTo(1)
    }

    @Test
    fun theRingLogShowsEventsAndReasons() {
        val log = listOf(
            LogLine(RingLogEvent("m1", RingLogType.MISSED, T0, RingLogEvent.REASON_MAX_RING_BACKS), "Drink water", SourceType.HABIT),
            LogLine(RingLogEvent("x", RingLogType.FAILED, T0.minus(hours(1)), "fgs_start_denied"), null, null),
        )
        show(HistoryUiState(loading = false, model = model, showLog = true, log = log))

        rule.onNodeWithTag(HistoryTags.LOG).assertIsDisplayed()
        rule.onNodeWithText(RingLogEvent.REASON_MAX_RING_BACKS).assertIsDisplayed()
        rule.onNodeWithText("FAILED").assertIsDisplayed()
    }
}
