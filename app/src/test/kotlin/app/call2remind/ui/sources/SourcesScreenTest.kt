package app.call2remind.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.SourceIds
import app.call2remind.sync.SyncState
import app.call2remind.sync.SyncStatus
import app.call2remind.testing.T0
import app.call2remind.testing.minutes
import app.call2remind.ui.components.SheetHostLayer
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SourcesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val toggles = mutableListOf<Pair<SourceType, Boolean>>()
    private val fixes = mutableListOf<SourceType>()

    private fun status(type: SourceType, state: SyncState, minutesAgo: Long? = 3) =
        SyncStatus(type, SourceIds.forType(type), state, minutesAgo?.let { T0.minus(minutes(it)) })

    private val state = SourcesUiState(
        loading = false,
        statuses = listOf(
            status(SourceType.CALENDAR, SyncState.Idle),
            status(SourceType.BIRTHDAY, SyncState.NeedsPermission("android.permission.READ_CONTACTS"), null),
            status(SourceType.GOOGLE_TASKS, SyncState.NotConnected, null),
            status(SourceType.MS_TODO, SyncState.NotConnected, null),
            status(SourceType.SAMSUNG_REMINDER, SyncState.Disabled, null),
        ),
        settings = SourceSettings(),
    )

    private fun show(s: SourcesUiState = state) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SheetHostLayer {
                    SourcesScreen(
                        state = s,
                        now = T0,
                        actions = SourcesActions(
                            onToggle = { type, on -> toggles += type to on },
                            onFix = { type, _ -> fixes += type },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun rowsShowStatusAndLastSync() {
        show()

        rule.onNodeWithText(context.getString(R.string.source_state_synced, "3 min ago")).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.source_state_needs_contacts)).assertIsDisplayed()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.sources_summary_attention, 1, 1)).assertIsDisplayed()
    }

    @Test
    fun tappingASourceTogglesIt() {
        show()

        rule.onNodeWithTag(SourcesTags.row(SourceType.CALENDAR)).assertIsOn().performClick()
        rule.onNodeWithTag(SourcesTags.row(SourceType.SAMSUNG_REMINDER)).performScrollTo().assertIsOff().performClick()

        assertThat(toggles).containsExactly(SourceType.CALENDAR to false, SourceType.SAMSUNG_REMINDER to true).inOrder()
    }

    @Test
    fun aMissingPermissionOffersAllow() {
        show()

        rule.onNodeWithTag(SourcesTags.action(SourceType.BIRTHDAY)).performClick()

        assertThat(fixes).containsExactly(SourceType.BIRTHDAY)
    }

    @Test
    fun connectExplainsThatSignInIsComing() {
        show()

        rule.onNodeWithTag(SourcesTags.action(SourceType.GOOGLE_TASKS)).performScrollTo().performClick()

        rule.onNodeWithTag(SourcesTags.CONNECT_SHEET).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.sources_connect_heading, context.getString(R.string.source_google_tasks))).assertIsDisplayed()
        // Nothing pretends to connect.
        assertThat(toggles).isEmpty()
    }

    @Test
    fun theCalendarPickerListsCalendars() {
        val withCalendars = state.copy(
            calendars = listOf(
                CalendarChoice(app.call2remind.sources.calendar.CalendarInfo(1, "Work", "me@work", "com.google"), included = true),
                CalendarChoice(app.call2remind.sources.calendar.CalendarInfo(2, "Personal", "me@home", "com.google"), included = false),
            ),
        )
        show(withCalendars)

        rule.onNodeWithTag(SourcesTags.CALENDAR_PICKER).performClick()

        rule.onNodeWithText("Work").assertIsDisplayed().assertIsOn()
        rule.onNodeWithText("Personal").assertIsDisplayed()
    }
}
