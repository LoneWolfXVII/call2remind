package app.call2remind.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.call2remind.ui.theme.C2RTheme

/** Scale pressed elements are squeezed to. */
const val PRESSED_SCALE = 0.97f

/**
 * Squeezes the element to [pressedScale] while [interactionSource] is pressed and springs back
 * (default spring: a hint of overshoot on release). Drawn in the layer, so no relayout.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = PRESSED_SCALE): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = C2RTheme.motion.default(0.0005f),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
