package app.call2remind.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.call2remind.ui.theme.C2RMotion
import app.call2remind.ui.theme.C2RTheme
import kotlinx.coroutines.delay

/**
 * One orchestrated entrance: the element rises [distance] and fades in on the default spring,
 * [index] × 30 ms after [key] changes (or first composition). Reduced motion: shown at once.
 * Runs in the graphics layer only (no relayout).
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, key: Any? = Unit, distance: Dp = 16.dp): Modifier {
    val motion = C2RTheme.motion
    val progress = remember(key) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(index * C2RMotion.STAGGER_MS)
            progress.animateTo(1f, motion.default())
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance.toPx()
    }
}
