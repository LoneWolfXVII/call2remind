package app.call2remind.ui.call

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ringing.CallUiState
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CallScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = Instant.parse("2026-10-02T09:30:00Z")
    private val ringing = CallUiState(
        loading = false,
        occurrenceId = "o1",
        title = "Standup",
        sourceType = SourceType.CALENDAR,
        fireAt = at,
    )

    private var answers = 0
    private var declines = 0
    private val snoozes = mutableListOf<Duration>()

    private fun show(state: CallUiState = ringing, presentation: CallPresentation = CallPresentation(transcript = "Reminder: Standup.")) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                CallScreen(
                    state = state,
                    presentation = presentation,
                    speech = SpeechProgress(),
                    actions = CallActions(
                        onAnswer = { answers++ },
                        onDecline = { declines++ },
                        onSnooze = { snoozes += it },
                    ),
                    now = { at },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun plugLeft(): Float =
        rule.onNodeWithTag(CallTags.PLUG, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    @Test
    fun slidingThePlugIntoTheSocketAnswers() {
        show()

        rule.onNodeWithTag(CallTags.PLUG, useUnmergedTree = true).performTouchInput {
            swipeRight(startX = centerX, endX = centerX + 330f, durationMillis = 400)
        }
        rule.waitForIdle()

        assertThat(answers).isEqualTo(1)
    }

    @Test
    fun releasingEarlySpringsBackWithoutAnswering() {
        show()
        val start = plugLeft()

        rule.onNodeWithTag(CallTags.PLUG, useUnmergedTree = true).performTouchInput {
            swipeRight(startX = centerX, endX = centerX + 40f, durationMillis = 400)
        }
        rule.waitForIdle()

        assertThat(answers).isEqualTo(0)
        assertThat(plugLeft()).isWithin(1f).of(start)
    }

    @Test
    fun accessibilityAnswerActionAnswers() {
        show()

        rule.onNodeWithContentDescription(context.getString(R.string.action_answer))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertThat(answers).isEqualTo(1)
    }

    @Test
    fun tappingSnoozeDeclinesWithTheDefaultLength() {
        show()

        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 5L)).performClick()

        assertThat(declines).isEqualTo(1)
    }

    @Test
    fun aSubMinuteSnoozeReadsInSecondsNeverZeroMinutes() {
        show(presentation = CallPresentation(transcript = "Reminder: Standup.", defaultSnooze = Duration.ofSeconds(30)))

        rule.onNodeWithText(context.getString(R.string.call_snooze_seconds, 30L)).assertIsDisplayed().performClick()
        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 0L)).assertDoesNotExist()

        assertThat(declines).isEqualTo(1)
    }

    @Test
    fun holdingSnoozeOpensTheSheetAndSnoozesForTheChosenLength() {
        show()

        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 5L)).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(CallTags.SNOOZE_SHEET).assertIsDisplayed()
        rule.onNodeWithTag(CallTags.snoozeChip(0)).assertIsSelected()
        rule.onNodeWithTag(CallTags.snoozeChip(2)).assertIsNotSelected().performClick()
        rule.onNodeWithTag(CallTags.snoozeChip(2)).assertIsSelected()
        rule.onNodeWithTag(CallTags.SNOOZE_CONFIRM)
            .assertTextEquals(context.getString(R.string.snooze_confirm, 15L))
            .performClick()
        rule.waitForIdle()

        assertThat(snoozes).containsExactly(Duration.ofMinutes(15))
        assertThat(declines).isEqualTo(0)
    }

    @Test
    fun answeredSnoozeOpensTheSheet() {
        show(state = ringing.copy(answered = true))

        rule.onNodeWithText(context.getString(R.string.action_snooze)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(CallTags.snoozeChip(1)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(CallTags.snoozeChip(1)).assertIsSelected()
        rule.onNodeWithTag(CallTags.SNOOZE_CONFIRM).performClick()
        rule.waitForIdle()

        assertThat(snoozes).containsExactly(Duration.ofMinutes(10))
    }

    @Test
    fun withoutRingBacksLeftTheSnoozeOptionIsHidden() {
        show(presentation = CallPresentation(canSnooze = false, ringBacks = 3, maxRingBacks = 3))

        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 5L)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.call_decline_to_missed)).assertIsDisplayed()
    }

    @Test
    fun snoozeSheetHidesItsOptionsWhenSnoozeIsNotAllowed() {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SnoozeSheetContent(
                    presentation = CallPresentation(canSnooze = false),
                    answered = false,
                    now = at,
                    onSnooze = { snoozes += it },
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText(context.getString(R.string.snooze_title)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.snooze_minutes, 5L)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.snooze_confirm, 5L)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.snooze_none_title)).assertIsDisplayed()
    }

    @Test
    fun snoozeSheetChipsChangeTheLength() {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                SnoozeSheetContent(
                    presentation = CallPresentation(),
                    answered = true,
                    now = at,
                    onSnooze = { snoozes += it },
                    onBack = {},
                )
            }
        }

        rule.onNodeWithTag(CallTags.snoozeChip(1)).performClick().assertIsSelected()
        rule.onNodeWithTag(CallTags.SNOOZE_CONFIRM).assertTextEquals(context.getString(R.string.snooze_confirm, 10L)).performClick()

        assertThat(snoozes).containsExactly(Duration.ofMinutes(10))
    }

    @Test
    fun snoozeSheetShowsRingBacksLeftForUnansweredCalls() {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SnoozeSheetContent(
                    presentation = CallPresentation(ringBacks = 1, maxRingBacks = 3),
                    answered = false,
                    now = at,
                    onSnooze = { snoozes += it },
                    onBack = {},
                )
            }
        }

        val summary = context.resources.getQuantityString(R.plurals.snooze_ringbacks_left, 2, 2, 3)
        val detail = context.getString(R.string.snooze_ringbacks_then_missed)
        rule.onNodeWithContentDescription("$summary $detail").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.snooze_confirm, 5L)).performClick()
        assertThat(snoozes).containsExactly(Duration.ofMinutes(5))
    }

    @Test
    fun answeredCallShowsDoneAndTheTranscript() {
        var done = 0
        rule.setContent {
            Call2RemindTheme(darkTheme = true, reduceMotion = false) {
                CallScreen(
                    state = ringing.copy(answered = true),
                    presentation = CallPresentation(transcript = "Reminder: Standup.", spokenText = "Reminder: Standup.", answeredAt = at),
                    speech = SpeechProgress(),
                    actions = CallActions(onDone = { done++ }),
                    now = { at },
                )
            }
        }

        rule.onNodeWithContentDescription("Reminder: Standup.").assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.action_done)).performClick()
        assertThat(done).isEqualTo(1)
    }
}
