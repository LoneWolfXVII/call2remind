package app.call2remind.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii by role (not one radius for everything): the more "object-like" the element, the
 * rounder it is. Buttons are pills, the big panels are 28, list cards 20, chips 18.
 */
@Immutable
data class C2RShapes(
    val pill: Shape = RoundedCornerShape(percent = 50),
    /** Welcome / self-test panel, bottom sheet top corners. */
    val panel: Shape = RoundedCornerShape(28.dp),
    /** Caller-ID strip on Home. */
    val strip: Shape = RoundedCornerShape(24.dp),
    /** Grouped settings / list cards. */
    val card: Shape = RoundedCornerShape(20.dp),
    /** Snooze chips. */
    val chip: Shape = RoundedCornerShape(18.dp),
    /** The time box on the incoming call, banners. */
    val box: Shape = RoundedCornerShape(16.dp),
    /** Icon tiles in permission rows. */
    val tile: Shape = RoundedCornerShape(14.dp),
    /** App mark, small tiles. */
    val tileSmall: Shape = RoundedCornerShape(10.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
)

/** 4 dp spacing scale; [gutter] is the screen edge padding every screen shares. */
@Immutable
data class C2RSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 40.dp,
    val gutter: Dp = 24.dp,
    /** Minimum touch target. */
    val touch: Dp = 48.dp,
)

val LocalC2RShapes = staticCompositionLocalOf { C2RShapes() }
val LocalC2RSpacing = staticCompositionLocalOf { C2RSpacing() }
