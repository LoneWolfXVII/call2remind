package app.call2remind.ui.detail

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.reminder
import app.call2remind.ui.components.SheetHostLayer
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DetailScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ringing = mutableListOf<Boolean>()
    private var skips = 0
    private var edits = 0
    private var deletes = 0

    private fun show(state: DetailUiState) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                SheetHostLayer {
                    DetailScreen(
                        state = state,
                        sharedKey = "row-x",
                        now = T0,
                        zone = UTC,
                        actions = DetailActions(
                            onSetRinging = { ringing += it },
                            onSkip = { skips++ },
                            onEditInSource = { edits++ },
                            onEditHabit = { edits++ },
                            onDeleteHabit = { deletes++ },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun stateFor(sourceType: SourceType, schedule: Schedule): DetailUiState {
        val r = reminder("x", schedule, sourceType = sourceType, title = "Standup")
        val o = Occurrence.scheduled(r, T0.plus(minutes(30)))
        return DetailUiState(loading = false, reminder = r, occurrence = o, lastSyncAt = T0.minus(minutes(3)))
    }

    @Test
    fun aCalendarEventExplainsItselfAndOffersItsSourceApp() {
        show(stateFor(SourceType.CALENDAR, Schedule.At(T0.plus(minutes(30)))))

        rule.onNodeWithText(context.getString(R.string.detail_next_ring_today)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.detail_decline_ringback, 5L, 3)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.detail_synced_notice, context.getString(R.string.source_calendar), "3 min ago"))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithTag(DetailTags.EDIT).performClick()
        rule.onNodeWithTag(DetailTags.SKIP).performClick()

        assertThat(edits).isEqualTo(1)
        assertThat(skips).isEqualTo(1)
    }

    @Test
    fun theRingToggleTurnsTheReminderOff() {
        show(stateFor(SourceType.CALENDAR, Schedule.At(T0.plus(hours(1)))))

        rule.onNodeWithTag(DetailTags.RING_TOGGLE).performScrollTo().assertIsOn().performClick()

        assertThat(ringing).containsExactly(false)
    }

    @Test
    fun deletingAHabitAsksFirst() {
        val rule7 = RecurrenceRule(setOf(DayOfWeek.MONDAY), setOf(LocalTime.of(7, 0)), LocalDate.of(2026, 3, 1))
        show(stateFor(SourceType.HABIT, Schedule.Recurring(rule7)))

        rule.onNodeWithTag(DetailTags.DELETE).performClick()
        assertThat(deletes).isEqualTo(0)
        rule.onNodeWithTag(DetailTags.DELETE_CONFIRM).performScrollTo().performClick()

        assertThat(deletes).isEqualTo(1)
    }
}
