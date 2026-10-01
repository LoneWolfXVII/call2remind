package app.call2remind.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens. Springs everywhere so every animation is interruptible and keeps its velocity
 * when retargeted:
 * - [default]: dampingRatio 0.7, StiffnessMediumLow — most UI movement.
 * - [playful]: dampingRatio 0.5 — confirmations (plug snaps home, toggles, lamps lighting).
 * - [precise]: dampingRatio 1.0 — layout, screen transitions, anything that must not overshoot.
 *
 * With "Remove animations" (animator duration scale 0) [reduced] is true: movement snaps and only
 * a short crossfade ([fade]) remains, so state changes are still perceivable.
 */
@Immutable
class C2RMotion(val reduced: Boolean = false) {

    fun <T> default(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(DEFAULT_DAMPING, Spring.StiffnessMediumLow, visibilityThreshold)

    fun <T> playful(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(PLAYFUL_DAMPING, Spring.StiffnessMedium, visibilityThreshold)

    fun <T> precise(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(PRECISE_DAMPING, Spring.StiffnessMediumLow, visibilityThreshold)

    /** Opacity changes: a critically damped spring, or a short tween when animations are off. */
    fun <T> fade(): FiniteAnimationSpec<T> =
        if (reduced) tween(REDUCED_FADE_MS) else spring(PRECISE_DAMPING, Spring.StiffnessMediumLow)

    /** Offsets for slides (IntOffset needs its own visibility threshold). */
    fun slide(): FiniteAnimationSpec<IntOffset> =
        if (reduced) snap() else spring(PRECISE_DAMPING, Spring.StiffnessMediumLow, IntOffset(1, 1))

    fun fadeInOnly(): EnterTransition = fadeIn(fade())

    fun fadeOutOnly(): ExitTransition = fadeOut(fade())

    companion object {
        const val DEFAULT_DAMPING = 0.7f
        const val PLAYFUL_DAMPING = 0.5f
        const val PRECISE_DAMPING = 1f
        const val REDUCED_FADE_MS = 120

        /** Stagger between items that enter together (waveform bars, list rows). */
        const val STAGGER_MS = 30L

        /** True when the user turned animations off (Developer options / Accessibility). */
        fun animationsDisabled(context: Context): Boolean =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

val LocalC2RMotion = staticCompositionLocalOf { C2RMotion() }
