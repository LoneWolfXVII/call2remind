package app.call2remind.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.settings.Settings
import app.call2remind.settings.ThemeMode
import app.call2remind.ui.components.SheetHostLayer
import app.call2remind.ui.components.SheetTags
import app.call2remind.ui.components.TimePickerTags
import app.call2remind.ui.ringtone.RingtoneTarget
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val times = mutableListOf<Pair<SourceType, LocalTime>>()
    private val ringSeconds = mutableListOf<Long>()
    private val themes = mutableListOf<ThemeMode>()
    private val ringtones = mutableListOf<RingtoneTarget>()
    private var testCalls = 0

    private fun show() {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SheetHostLayer {
                    SettingsScreen(
                        state = SettingsUiState(loading = false, settings = Settings()),
                        nav = SettingsNav(openRingtone = { ringtones += it }, openTestCall = { testCalls++ }),
                        actions = SettingsActions(
                            onDefaultTime = { type, time -> times += type to time },
                            onRingSeconds = { ringSeconds += it },
                            onTheme = { themes += it },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun changingTheAllDayTimeThroughTheMonoPicker() {
        show()

        rule.onNodeWithTag(SettingsTags.defaultTime(SourceType.CALENDAR)).performScrollTo().performClick()
        rule.onNodeWithTag(SheetTags.SHEET).assertIsDisplayed()
        // 9:00 → 7:00: spin the hour wheel.
        rule.onNodeWithTag(TimePickerTags.HOURS).performScrollToIndex(7)
        rule.waitForIdle()
        rule.onNodeWithTag(TimePickerTags.CONFIRM).performScrollTo().performClick()
        rule.waitForIdle()

        assertThat(times).containsExactly(SourceType.CALENDAR to LocalTime.of(7, 0))
    }

    @Test
    fun ringDurationIsASegmentedChoice() {
        show()

        rule.onNodeWithText(context.getString(R.string.settings_seconds, 45L)).assertIsSelected()
        rule.onNodeWithText(context.getString(R.string.settings_seconds, 60L)).performClick()

        assertThat(ringSeconds).containsExactly(60L)
    }

    @Test
    fun themeAndRingtoneAndTestCall() {
        show()

        rule.onNodeWithText(context.getString(R.string.settings_theme_dark)).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTags.ringtone(SourceType.BIRTHDAY)).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTags.TEST_CALL).performScrollTo().performClick()

        assertThat(themes).containsExactly(ThemeMode.DARK)
        assertThat(ringtones).containsExactly(RingtoneTarget.Source(SourceType.BIRTHDAY))
        assertThat(testCalls).isEqualTo(1)
    }
}
