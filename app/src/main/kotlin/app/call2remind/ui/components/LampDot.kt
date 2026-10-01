package app.call2remind.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RTheme

/**
 * The exchange lamp: the brand's status light.
 *
 * - lit: a solid [C2RTheme.colors.lamp] disc; [halo] adds the 4 dp soft ring the designs use for
 *   "live" lamps; [pulse] radiates two staggered rings (the ringing call).
 * - unlit: a 2 dp ring in [unlitColor].
 *
 * Switching between lit and unlit springs (playful): the disc overshoots slightly as it lights,
 * like a filament catching.
 */
@Composable
fun LampDot(
    lit: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    halo: Boolean = false,
    pulse: Boolean = false,
    litColor: Color = C2RTheme.colors.lamp,
    unlitColor: Color = C2RTheme.colors.muted,
) {
    val motion = C2RTheme.motion
    val fill by animateFloatAsState(if (lit) 1f else 0f, motion.playful(0.001f), label = "lampFill")
    val ringColor by animateColorAsState(if (lit) litColor else unlitColor, motion.fade(), label = "lampRing")
    val showPulse = pulse && lit && !motion.reduced
    // Room for the halo / pulse to draw outside the dot without affecting layout.
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (showPulse) {
            LampPulse(color = litColor, dotSize = size)
        }
        Canvas(Modifier.size(size)) {
            drawLamp(fill, ringColor, litColor, halo && lit)
        }
    }
}

private fun DrawScope.drawLamp(fill: Float, ringColor: Color, litColor: Color, halo: Boolean) {
    val radius = size.minDimension / 2f
    val stroke = 2.dp.toPx().coerceAtMost(radius)
    if (halo) {
        drawCircle(litColor.copy(alpha = 0.28f * fill.coerceIn(0f, 1f)), radius = radius + 4.dp.toPx())
    }
    // Unlit ring fades as the fill grows over it.
    if (fill < 1f) {
        drawCircle(ringColor, radius = radius - stroke / 2f, style = Stroke(stroke))
    }
    if (fill > 0f) {
        drawCircle(litColor, radius = radius * fill.coerceAtMost(1.15f))
    }
}

/** Two rings expanding from the dot (1.6 s, the second offset by half a period). */
@Composable
fun LampPulse(color: Color, dotSize: Dp, modifier: Modifier = Modifier, maxScale: Float = 1.9f, periodMs: Int = 1_600) {
    val transition = rememberInfiniteTransition(label = "lampPulse")
    val a by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseA",
    )
    val b by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart, StartOffset(periodMs / 2)),
        label = "pulseB",
    )
    Canvas(modifier.size(dotSize)) {
        val r = size.minDimension / 2f
        for (t in floatArrayOf(a, b)) {
            val eased = 1f - (1f - t) * (1f - t) // ease-out
            drawCircle(
                color = color.copy(alpha = 0.55f * (1f - eased)),
                radius = r * (1f + (maxScale - 1f) * eased),
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
    }
}
