package app.call2remind.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.call2remind.ui.theme.C2RTheme
import kotlin.math.ceil

/**
 * The sync button. It spins only while [syncing]; when the sync ends, the current turn finishes on
 * the playful spring (a small overshoot and settle) instead of snapping back. Reduced motion: no spin.
 */
@Composable
fun SyncButton(
    syncing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = C2RTheme.motion
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(syncing) {
        if (syncing && !motion.reduced) {
            while (true) {
                rotation.animateTo(rotation.value + FULL_TURN, tween(SPIN_MS, easing = LinearEasing))
            }
        } else {
            rotation.animateTo(ceil(rotation.value / FULL_TURN) * FULL_TURN, motion.playful())
        }
    }
    CircleIconButton(
        icon = C2RIcons.Sync,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier.graphicsLayer { rotationZ = rotation.value },
    )
}

private const val SPIN_MS = 900
private const val FULL_TURN = 360f
