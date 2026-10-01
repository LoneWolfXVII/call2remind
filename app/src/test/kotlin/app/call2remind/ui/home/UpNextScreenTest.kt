package app.call2remind.ui.home

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import app.call2remind.ui.components.UndoBarTags
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
class UpNextScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun row(id: String, at: Instant, state: OccurrenceState = OccurrenceState.SCHEDULED): OccurrenceWithReminder {
        val r = reminder(id, Schedule.At(at), sourceType = SourceType.CALENDAR, title = id)
        return OccurrenceWithReminder(occurrence(r, at, state = state), r)
    }

    private val standup = row("Standup", T0.plus(minutes(20)))
    private val gym = row("Gym", T0.plus(hours(10)))
    private val model = Timeline.build(listOf(standup, gym), emptyList(), T0, UTC)

    private val swipes = mutableListOf<Pair<String, SwipeKind>>()
    private val opened = mutableListOf<Pair<String, String>>()
    private var undos = 0
    private var newHabits = 0

    private fun show(state: UpNextUiState) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                UpNextScreen(
                    state = state,
                    now = T0,
                    zone = UTC,
                    actions = UpNextActions(
                        onOpen = { r, key -> opened += r.title to key },
                        onSwipe = { r, kind -> swipes += r.title to kind },
                        onUndo = { undos++ },
                        onNewHabit = { newHabits++ },
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun gymRow() = rule.onNodeWithTag(UpNextTags.row(gym.occurrence.id))

    @Test
    fun showsTheNextCallOnTheStripAndTheTimeline() {
        show(UpNextUiState(loading = false, model = model))

        rule.onNodeWithTag(UpNextTags.STRIP).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.upnext_next_call, "in 20 min")).assertIsDisplayed()
        gymRow().assertIsDisplayed()
    }

    @Test
    fun swipingRightMarksDone() {
        show(UpNextUiState(loading = false, model = model))

        gymRow().performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 300) }
        rule.waitForIdle()

        assertThat(swipes).containsExactly("Gym" to SwipeKind.DONE)
    }

    @Test
    fun swipingLeftSkipsToday() {
        show(UpNextUiState(loading = false, model = model))

        gymRow().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 300) }
        rule.waitForIdle()

        assertThat(swipes).containsExactly("Gym" to SwipeKind.SKIP)
    }

    @Test
    fun aShortSwipeSpringsBackWithoutActing() {
        show(UpNextUiState(loading = false, model = model))

        gymRow().performTouchInput { swipeRight(startX = centerX, endX = centerX + 60f, durationMillis = 400) }
        rule.waitForIdle()

        assertThat(swipes).isEmpty()
    }

    @Test
    fun swipeActionsAreAvailableToAccessibility() {
        show(UpNextUiState(loading = false, model = model))
        val done = context.getString(R.string.swipe_done)
        val hasDone = SemanticsMatcher("has a Done action") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == done } == true
        }

        val node = rule.onNode(hasDone and hasAnyAncestor(hasTestTag(UpNextTags.row(gym.occurrence.id))), useUnmergedTree = true)
            .fetchSemanticsNode()
        rule.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == done }.action() }

        assertThat(swipes).containsExactly("Gym" to SwipeKind.DONE)
    }

    @Test
    fun tappingARowOpensItsDetail() {
        show(UpNextUiState(loading = false, model = model))

        rule.onNodeWithText("Gym").performClick()

        assertThat(opened).containsExactly("Gym" to UpNextShared.row(gym.occurrence.id))
    }

    @Test
    fun theUndoBarOffersUndo() {
        val pending = PendingSwipe(standup.occurrence.id, "Standup", SwipeKind.DONE)
        show(UpNextUiState(loading = false, model = Timeline.build(listOf(gym), emptyList(), T0, UTC), pending = pending))

        rule.onNodeWithText(context.getString(R.string.undo_done, "Standup")).assertIsDisplayed()
        rule.onNodeWithTag(UndoBarTags.UNDO).performClick()

        assertThat(undos).isEqualTo(1)
    }

    @Test
    fun emptyStateInvitesAHabitOrASourceCheck() {
        show(UpNextUiState(loading = false, model = UpNextModel()))

        rule.onNodeWithTag(UpNextTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.empty_title)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.action_new_habit)).performClick()

        assertThat(newHabits).isEqualTo(1)
    }

    @Test
    fun ringingRowSitsAboveTheTimeline() {
        val ringing = row("Pills", T0.minus(minutes(1)), OccurrenceState.RINGING)
        show(UpNextUiState(loading = false, model = Timeline.build(listOf(ringing, gym), emptyList(), T0, UTC)))

        rule.onNodeWithText(context.getString(R.string.upnext_ringing_now)).assertIsDisplayed()
        rule.onNodeWithText("Pills").assertIsDisplayed()
    }
}
