package app.call2remind.ui.call

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CallScreenBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = Instant.parse("2026-10-02T09:30:00Z")
    private var backs = 0
    private var declines = 0

    private fun show(answered: Boolean = false) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                CallScreen(
                    state = CallUiState(
                        loading = false,
                        occurrenceId = "o1",
                        title = "Standup",
                        sourceType = SourceType.CALENDAR,
                        fireAt = at,
                        answered = answered,
                    ),
                    presentation = CallPresentation(transcript = "Reminder: Standup."),
                    speech = SpeechProgress(),
                    actions = CallActions(onDecline = { declines++ }, onBack = { backs++ }),
                    now = { at },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun backWhileRingingSendsTheCallToTheBackground() {
        show()

        pressBack()

        assertThat(backs).isEqualTo(1)
        assertThat(declines).isEqualTo(0)
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun backWhileAnsweredSendsTheCallToTheBackground() {
        show(answered = true)

        pressBack()

        assertThat(backs).isEqualTo(1)
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test
    fun backClosesTheSnoozeSheetFirst() {
        show()
        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 5L)).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(CallTags.SNOOZE_SHEET).assertIsDisplayed()

        pressBack()

        rule.onNodeWithTag(CallTags.SNOOZE_SHEET).assertDoesNotExist()
        assertThat(backs).isEqualTo(0)

        pressBack()
        assertThat(backs).isEqualTo(1)
    }
}
