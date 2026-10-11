package app.call2remind.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class TimeWheelAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun earlierAndLaterLiveOnTheWheelTalkBackFocuses() {
        var picked = LocalTime.of(9, 0)
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                var time by remember { mutableStateOf(picked) }
                MonoTimePicker(
                    time = time,
                    onTimeChange = {
                        time = it
                        picked = it
                    },
                    is24Hour = true,
                )
            }
        }
        val later = context.getString(R.string.time_picker_later)
        val earlier = context.getString(R.string.time_picker_earlier)
        val wheel = rule.onNodeWithTag(TimePickerTags.HOURS, useUnmergedTree = true).fetchSemanticsNode()

        assertThat(wheel.config.isMergingSemanticsOfDescendants).isTrue()
        assertThat(wheel.config[SemanticsActions.CustomActions].map { it.label }).containsExactly(earlier, later)

        rule.runOnIdle { wheel.config[SemanticsActions.CustomActions].first { it.label == later }.action() }
        rule.waitForIdle()

        assertThat(picked).isEqualTo(LocalTime.of(10, 0))
    }
}
