package app.call2remind.ui.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Scroll-linked collapse of the "next call" strip ("exit until collapsed"):
 * - scrolling the list up first collapses the strip (pre-scroll), only then scrolls the list;
 * - scrolling down expands it only once the list is back at its top (post-scroll);
 * - a fling that stops half way settles to the nearer end on [settleSpec].
 *
 * [offsetPx] runs from 0 (expanded) to `-maxCollapsePx` (collapsed); [fraction] is 0…1. Reads are
 * snapshot state, so layout / draw that read them in their own phase never recompose.
 */
@Stable
class CollapsingHeaderState(initialFraction: Float = 0f) {
    private var restoreFraction: Float? = initialFraction.takeIf { it > 0f }
    private var maxState by mutableFloatStateOf(0f)

    /** How far the strip can collapse (its full height), set by the layout. */
    var maxCollapsePx: Float
        get() = maxState
        set(value) {
            val max = value.coerceAtLeast(0f)
            if (max == maxState) return
            // Keep the same fraction when the strip's height changes (e.g. a longer title), or
            // restore the saved one the first time the height is known.
            val keep = restoreFraction ?: fraction
            restoreFraction = null
            maxState = max
            offsetPx = -keep * max
        }

    var offsetPx: Float by mutableFloatStateOf(0f)
        private set

    val fraction: Float
        get() = if (maxCollapsePx <= 0f || offsetPx >= 0f) 0f else (-offsetPx / maxCollapsePx).coerceIn(0f, 1f)

    /** True once the strip is mostly gone: the compact bar takes over (and the FAB shrinks). */
    val isCollapsed: Boolean by derivedStateOf { fraction >= COLLAPSED_AT }

    var settleSpec: AnimationSpec<Float>? = null

    /** Moves the header by [delta] px (negative = collapse); returns what it consumed. */
    fun consume(delta: Float): Float {
        val old = offsetPx
        offsetPx = (old + delta).coerceIn(-maxCollapsePx, 0f)
        return offsetPx - old
    }

    /** Snaps to the nearer end if the header stopped in between. */
    suspend fun settle() {
        val f = fraction
        if (f <= 0f || f >= 1f || maxCollapsePx <= 0f) return
        val target = if (f < SETTLE_AT) 0f else -maxCollapsePx
        val spec = settleSpec
        if (spec == null) {
            offsetPx = target
        } else {
            animate(offsetPx, target, animationSpec = spec) { value, _ -> offsetPx = value }
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    companion object {
        const val COLLAPSED_AT = 0.6f
        const val SETTLE_AT = 0.5f

        val Saver: Saver<CollapsingHeaderState, Float> = Saver(
            save = { it.fraction },
            restore = { CollapsingHeaderState(it) },
        )
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState =
    rememberSaveable(saver = CollapsingHeaderState.Saver) { CollapsingHeaderState() }
