package app.call2remind.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a swipe direction does: its label (also the TalkBack action) and how the reveal looks. */
@Immutable
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

/**
 * Pure swipe decision, separated for unit tests: where a released row goes.
 * - past the threshold (and not flung back), or flung fast enough in the direction it is already
 *   pulled → commits;
 * - otherwise it springs back.
 */
object SwipePhysics {
    enum class Outcome { START, END, BACK }

    /** Commit distance: a third of the row, at most [maxPx]. */
    fun threshold(widthPx: Float, maxPx: Float): Float = minOf(widthPx * THRESHOLD_FRACTION, maxPx)

    fun release(offsetPx: Float, velocityPx: Float, thresholdPx: Float, flingPx: Float): Outcome = when {
        thresholdPx <= 0f -> Outcome.BACK
        offsetPx > 0f && ((offsetPx >= thresholdPx && velocityPx > -flingPx) || velocityPx > flingPx) -> Outcome.START
        offsetPx < 0f && ((offsetPx <= -thresholdPx && velocityPx < flingPx) || velocityPx < -flingPx) -> Outcome.END
        else -> Outcome.BACK
    }

    private const val THRESHOLD_FRACTION = 0.34f
}

/**
 * A list row that swipes: right reveals [start] (Done), left reveals [end] (Skip today).
 *
 * The row follows the finger 1:1. Crossing the threshold ticks (haptic) and pops the revealed
 * icon on the playful spring; releasing past it (or flinging) slides the row off on the precise
 * spring, confirms (haptic) and calls the action. Released short, it springs back home on the
 * default spring, carrying the release velocity, so a flick that doesn't make it wobbles back.
 * Both actions are also TalkBack custom actions: [content] receives them and puts them on its
 * clickable node, so TalkBack offers them where focus actually lands.
 */
@Composable
fun SwipeActionRow(
    start: SwipeAction,
    end: SwipeAction,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentBackground: Color = C2RTheme.colors.ground,
    content: @Composable (accessibilityActions: List<CustomAccessibilityAction>) -> Unit,
) {
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val maxThresholdPx = with(density) { 132.dp.toPx() }
    val flingPx = with(density) { 900.dp.toPx() }
    val threshold = SwipePhysics.threshold(widthPx, maxThresholdPx)
    val past by remember(threshold) { derivedStateOf { threshold > 0f && abs(offset.value) >= threshold } }
    val currentStart by rememberUpdatedState(onStart)
    val currentEnd by rememberUpdatedState(onEnd)

    LaunchedEffect(past) {
        if (past) haptics.perform(Haptic.SegmentTick)
    }
    val pop by animateFloatAsState(if (past) 1f else 0f, motion.playful(0.001f), label = "swipePop")

    val accessibilityActions = remember(enabled, start.label, end.label) {
        if (!enabled) {
            emptyList()
        } else {
            listOf(
                CustomAccessibilityAction(start.label) {
                    currentStart()
                    true
                },
                CustomAccessibilityAction(end.label) {
                    currentEnd()
                    true
                },
            )
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo(offset.value + delta) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        // The reveal behind the row: only the side being uncovered is painted. Recomposes only
        // when the drag changes direction; the per-frame work is in graphics layers.
        val fromStart by remember { derivedStateOf { offset.value >= 0f } }
        val side = if (fromStart) start else end
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (offset.value == 0f) 0f else 1f }
                .background(side.container)
                .padding(horizontal = 24.dp),
            horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.graphicsLayer {
                    val progress = if (threshold > 0f) (abs(offset.value) / threshold).coerceIn(0f, 1f) else 0f
                    val scale = 0.55f + 0.45f * progress + 0.18f * pop
                    scaleX = scale
                    scaleY = scale
                    alpha = (progress * 1.4f).coerceIn(0f, 1f)
                    val atLeft = fromStart != rtl
                    transformOrigin = TransformOrigin(if (atLeft) 0f else 1f, 0.5f)
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(side.icon, contentDescription = null, tint = side.content, modifier = Modifier.size(22.dp))
                Text(side.label, style = C2RTheme.type.button, color = side.content, maxLines = 1)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(contentBackground)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    // offset {} is mirrored in RTL, so the drag must be too.
                    reverseDirection = rtl,
                    onDragStopped = { velocity ->
                        when (SwipePhysics.release(offset.value, velocity, threshold, flingPx)) {
                            SwipePhysics.Outcome.START -> {
                                haptics.perform(Haptic.Confirm)
                                offset.animateTo(widthPx, motion.precise(), initialVelocity = velocity)
                                currentStart()
                            }
                            SwipePhysics.Outcome.END -> {
                                haptics.perform(Haptic.Confirm)
                                offset.animateTo(-widthPx, motion.precise(), initialVelocity = velocity)
                                currentEnd()
                            }
                            SwipePhysics.Outcome.BACK -> offset.animateTo(0f, motion.default(), initialVelocity = velocity)
                        }
                    },
                ),
        ) {
            content(accessibilityActions)
        }
    }
}
