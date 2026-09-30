package app.call2remind.ui.call

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.core.model.SourceType
import app.call2remind.ringing.CallUiState
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** Temporary probes: which layer keeps touches from the snooze sheet inside the call screen? */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SheetHitProbeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = Instant.parse("2026-10-02T09:30:00Z")
    private val snoozes = mutableListOf<Duration>()
    private var declines = 0

    private fun selected(tag: String): Boolean =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun callScreen(reduce: Boolean) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = reduce) {
                CallScreen(
                    state = CallUiState(loading = false, occurrenceId = "o1", title = "Standup", sourceType = SourceType.CALENDAR, fireAt = at),
                    presentation = CallPresentation(transcript = "x"),
                    speech = SpeechProgress(),
                    actions = CallActions(onSnooze = { snoozes += it }, onDecline = { declines++ }),
                    now = { at },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.call_snooze_minutes, 5L)).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    @Test
    fun p1ConfirmTouchInCallScreen() {
        callScreen(reduce = false)
        rule.onNodeWithTag(CallTags.SNOOZE_CONFIRM).performClick()
        rule.waitForIdle()
        assertWithMessage("P1 confirm touch in call screen: snoozes=$snoozes declines=$declines").that(snoozes).isNotEmpty()
    }

    @Test
    fun p2ChipTouchInCallScreenReducedMotion() {
        callScreen(reduce = true)
        rule.onNodeWithTag(CallTags.snoozeChip(2)).performClick()
        rule.waitForIdle()
        assertWithMessage("P2 chip touch, reduced motion: selected=${selected(CallTags.snoozeChip(2))}").that(selected(CallTags.snoozeChip(2))).isTrue()
    }

    private fun layered(scaledSibling: Boolean) {
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                Box(Modifier.fillMaxSize().background(C2RTheme.colors.panel)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (scaledSibling) Modifier.graphicsLayer { alpha = 0.45f; scaleX = 0.97f; scaleY = 0.97f } else Modifier)
                            .background(Color.Red),
                    )
                    Column(Modifier.matchParentSize()) {
                        Spacer(Modifier.weight(1f))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(0, 0) }
                                .clip(C2RTheme.shapes.sheet)
                                .background(C2RTheme.colors.surface),
                        ) {
                            SnoozeSheetContent(presentation = CallPresentation(), answered = true, now = at, onSnooze = { snoozes += it }, onBack = {})
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(CallTags.snoozeChip(2)).performClick()
        rule.waitForIdle()
    }

    @Test
    fun p3LayeredWithScaledSibling() {
        layered(scaledSibling = true)
        assertWithMessage("P3 layered + scaled sibling: selected=${selected(CallTags.snoozeChip(2))}").that(selected(CallTags.snoozeChip(2))).isTrue()
    }

    @Test
    fun p4LayeredPlain() {
        layered(scaledSibling = false)
        assertWithMessage("P4 layered plain: selected=${selected(CallTags.snoozeChip(2))}").that(selected(CallTags.snoozeChip(2))).isTrue()
    }

    @Test
    fun p5ChipTouchWithoutSheetInCallScreenHierarchy() {
        val open = mutableStateOf(false)
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                Box(Modifier.fillMaxSize()) {
                    SnoozeSheetContent(presentation = CallPresentation(), answered = true, now = at, onSnooze = { snoozes += it }, onBack = { open.value = true })
                }
            }
        }
        rule.onNodeWithTag(CallTags.snoozeChip(2)).performClick()
        rule.waitForIdle()
        assertWithMessage("P5 plain box: selected=${selected(CallTags.snoozeChip(2))}").that(selected(CallTags.snoozeChip(2))).isTrue()
    }
}
