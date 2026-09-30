package app.call2remind.ui.call

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.call2remind.ui.components.C2RIcons
import app.call2remind.ui.components.LampPulse
import app.call2remind.ui.theme.C2RTheme
import app.call2remind.ui.theme.Haptic
import app.call2remind.ui.theme.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Test tags for the call screen. */
object CallTags {
    const val PLUG = "call_plug"
    const val TRACK = "call_track"
    const val SOCKET = "call_socket"
    const val SNOOZE_SHEET = "call_snooze_sheet"
    const val SNOOZE_CONFIRM = "call_snooze_confirm"

    fun snoozeChip(index: Int) = "call_snooze_chip_$index"
}

/**
 * Pure physics of the plug drag, separated from Compose for unit tests.
 *
 * Resistance grows quadratically with progress (the plug gets "stiffer" as it nears the socket,
 * like pushing a real jack home); past either end the drag rubber-bands at [OVERSCROLL].
 */
internal object PlugPhysics {
    const val RESISTANCE = 0.5f
    const val OVERSCROLL = 0.15f
    const val COMMIT_PROGRESS = 0.8f
    const val FLING_COMMIT_PROGRESS = 0.45f

    /** Fling speed (dp/s) that commits from [FLING_COMMIT_PROGRESS]. */
    const val FLING_DP_PER_S = 1_000f

    /** Applies a raw drag [delta] to [offset] on a track of [max] px, with [overshoot] px of rubber band. */
    fun drag(offset: Float, delta: Float, max: Float, overshoot: Float): Float {
        if (max <= 0f) return 0f
        val progress = (offset / max).coerceIn(0f, 1f)
        val factor = when {
            delta > 0f && offset >= max -> OVERSCROLL
            delta > 0f -> 1f - RESISTANCE * progress * progress
            offset <= 0f -> OVERSCROLL
            else -> 1f
        }
        return (offset + delta * factor).coerceIn(-overshoot, max + overshoot)
    }

    /** Whether a release at [offset] with [velocity] (px/s; [flingThreshold] px/s) plugs in. */
    fun commits(offset: Float, velocity: Float, max: Float, flingThreshold: Float): Boolean {
        if (max <= 0f) return false
        val progress = offset / max
        return progress >= COMMIT_PROGRESS || (velocity >= flingThreshold && progress >= FLING_COMMIT_PROGRESS)
    }

    /** Detent index 0..3 for 25 / 50 / 75 %. */
    fun detent(offset: Float, max: Float): Int =
        if (max <= 0f) 0 else floor((offset / max).coerceIn(0f, 0.999f) * 4f).toInt()
}

/**
 * "Slide the amber plug into the socket" — the answer gesture.
 *
 * - Drag: follows the finger with growing resistance ([PlugPhysics]); a segment tick at 25, 50
 *   and 75 %.
 * - Release early: springs back (default spring, keeps the fling velocity), no answer.
 * - Release past 80 % (or a fling past 45 %): snaps home on the playful spring — it overshoots
 *   into the socket and settles — with a confirm haptic, and [onAnswer] fires immediately so the
 *   ringtone stops without waiting for the animation.
 * - TalkBack / Switch Access: the whole track is one "Answer" button (double tap answers);
 *   "Decline" is a custom action.
 *
 * @param onSocketCenter the socket's centre in root coordinates (the answered panel grows from it).
 */
@Composable
fun PlugSlider(
    label: String,
    answerLabel: String,
    declineLabel: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSocketCenter: (Offset) -> Unit = {},
) {
    val c = C2RTheme.colors
    val motion = C2RTheme.motion
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnAnswer by rememberUpdatedState(onAnswer)

    val plugSize = 64.dp
    val inset = 8.dp
    val overshootPx = with(density) { 14.dp.toPx() }
    val flingThreshold = with(density) { PlugPhysics.FLING_DP_PER_S.dp.toPx() }

    var maxTravel by remember { mutableFloatStateOf(0f) }
    val offset = remember { mutableFloatStateOf(0f) }
    var committed by remember { mutableStateOf(false) }
    var lastDetent by remember { mutableIntStateOf(0) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val socketLit = remember { Animatable(0f) }

    fun progress(): Float = if (maxTravel <= 0f) 0f else (offset.floatValue / maxTravel).coerceIn(0f, 1f)

    fun animateTo(target: Float, velocity: Float, playful: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val anim = Animatable(offset.floatValue)
            val spec = when {
                motion.reduced -> spring<Float>(stiffness = Spring.StiffnessHigh, dampingRatio = 1f)
                playful -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
                else -> spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
            }
            anim.animateTo(target, spec, initialVelocity = velocity) { offset.floatValue = value }
        }
    }

    fun commit(velocity: Float) {
        if (committed) return
        committed = true
        haptics.perform(Haptic.Confirm)
        currentOnAnswer()
        animateTo(maxTravel, velocity, playful = true)
        scope.launch { socketLit.animateTo(1f, motion.playful()) }
    }

    val dragState = rememberDraggableState { delta ->
        if (committed) return@rememberDraggableState
        val next = PlugPhysics.drag(offset.floatValue, delta, maxTravel, overshootPx)
        offset.floatValue = next
        val detent = PlugPhysics.detent(next, maxTravel)
        if (detent > lastDetent) haptics.perform(Haptic.SegmentTick)
        lastDetent = detent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .testTag(CallTags.TRACK)
            .onSizeChanged { size ->
                // Plug centre travels from inset+32 dp to the socket centre (width - 40 dp).
                maxTravel = with(density) { size.width - (inset + plugSize / 2).toPx() - 40.dp.toPx() }.coerceAtLeast(0f)
            }
            .drawBehind {
                val p = progress()
                // Track outline, and a lamp wash that fills behind the plug as it travels.
                val r = CornerRadius(size.height / 2f)
                val stroke = 1.5.dp.toPx()
                val plugRight = inset.toPx() + plugSize.toPx() + offset.floatValue
                if (p > 0f) {
                    drawRoundRect(
                        color = c.lamp.copy(alpha = 0.16f * p + 0.04f),
                        size = Size(plugRight.coerceAtMost(size.width), size.height),
                        cornerRadius = r,
                    )
                }
                drawRoundRect(
                    color = c.onPanelFaint,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(size.height / 2f - stroke / 2f),
                    style = Stroke(stroke),
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = answerLabel
                onClick(label = answerLabel) {
                    if (enabled) commit(0f)
                    enabled
                }
                customActions = listOf(
                    CustomAccessibilityAction(declineLabel) {
                        onDecline()
                        true
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // "Slide to answer" fades out as the plug covers it.
        Text(
            text = label,
            style = C2RTheme.type.button,
            color = c.onPanel,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = (1f - progress() * 1.8f).coerceIn(0f, 1f) },
        )

        Socket(
            lit = { socketLit.value },
            progress = ::progress,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp + inset)
                .size(56.dp)
                .testTag(CallTags.SOCKET)
                .onGloballyPositioned { onSocketCenter(it.boundsInRoot().center) },
        )

        Box(
            modifier = Modifier
                .padding(start = inset)
                .offset { IntOffset(offset.floatValue.roundToInt(), 0) }
                .size(plugSize)
                .testTag(CallTags.PLUG)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled && !committed,
                    startDragImmediately = true,
                    onDragStarted = {
                        settleJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        if (committed) return@draggable
                        if (PlugPhysics.commits(offset.floatValue, velocity, maxTravel, flingThreshold)) {
                            commit(velocity)
                        } else {
                            lastDetent = 0
                            animateTo(0f, velocity, playful = false)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!motion.reduced && enabled) {
                LampPulse(
                    color = c.lamp,
                    dotSize = plugSize,
                    modifier = Modifier.graphicsLayer { alpha = (1f - progress() * 3f).coerceIn(0f, 1f) },
                )
            }
            Box(
                Modifier
                    .size(plugSize)
                    .graphicsLayer {
                        // A slight squash while it is being pushed, relaxing as it seats.
                        val p = progress()
                        val squash = if (committed) 0f else 0.04f * p
                        scaleX = 1f + squash
                        scaleY = 1f - squash
                    }
                    .background(c.lamp, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(C2RIcons.Phone, contentDescription = null, tint = c.onLamp, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** The socket: a ring with a centre pin that warms towards lamp as the plug approaches. */
@Composable
private fun Socket(lit: () -> Float, progress: () -> Float, modifier: Modifier = Modifier) {
    val c = C2RTheme.colors
    Canvas(modifier) {
        val p = progress()
        val l = lit()
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = 22.dp.toPx() * (1f + 0.08f * p)
        val stroke = 2.dp.toPx()
        val ringColor = lerp(c.onPanelMuted, c.lamp, (p * p).coerceIn(0f, 1f))
        if (l > 0f) {
            drawCircle(c.lamp.copy(alpha = 0.28f * l.coerceIn(0f, 1f)), radius = ringRadius + 6.dp.toPx() * l, center = center)
        }
        drawCircle(ringColor, radius = ringRadius - stroke / 2f, center = center, style = Stroke(stroke))
        drawCircle(lerp(c.onPanelMuted, c.lamp, p), radius = 5.5.dp.toPx() * (1f + 0.6f * abs(l)), center = center)
    }
}
