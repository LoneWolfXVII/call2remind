@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.call2remind.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/** The app-wide [SharedTransitionScope] (the NavHost is inside one). */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The destination's [AnimatedVisibilityScope] (provided per NavHost destination). */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Keys for shared elements between destinations (Home → Detail in Phase 2). */
object SharedKeys {
    fun callerStrip(occurrenceId: String) = "caller-strip-$occurrenceId"

    fun title(occurrenceId: String) = "title-$occurrenceId"

    fun time(occurrenceId: String) = "time-$occurrenceId"
}

/** Default bounds motion for shared elements: the "default" spring (0.7, MediumLow). */
val C2RBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * `sharedBounds` when a shared transition scope and destination scope are available (i.e. inside
 * the NavHost), a no-op otherwise (previews, tests, the call activity). Use for containers that
 * change shape between screens (the caller-ID strip → the Detail header).
 */
@Composable
fun Modifier.sharedBoundsIfAvailable(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedBoundsIfAvailable.sharedBounds(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = C2RBoundsTransform,
        )
    }
}

/** `sharedElement` variant for content that is identical on both screens (the mono time, a title). */
@Composable
fun Modifier.sharedElementIfAvailable(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedElementIfAvailable.sharedElement(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = C2RBoundsTransform,
        )
    }
}
