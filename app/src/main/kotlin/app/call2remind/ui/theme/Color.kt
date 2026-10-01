package app.call2remind.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Exchange Lamp" colour tokens. Names follow the design files (docs/design/\*.dc.html).
 *
 * Rules the tokens encode:
 * - [lamp] is only ever a *fill* behind dark text ([onLamp]); never lamp-coloured text on a light ground.
 * - Primary buttons are [ink] filled with [surface] text, so the dark theme (light ink) flips them for free.
 * - The call surfaces ([panel]) look the same in both themes; that is the brand's constant.
 */
@Immutable
data class C2RColors(
    val ground: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val panel: Color,
    val onPanel: Color,
    val onPanelMuted: Color,
    val lamp: Color,
    val onLamp: Color,
    val missed: Color,
    val navBg: Color,
    val tint: Color,
    val focus: Color,
    val isDark: Boolean,
) {
    /** Hairline / outline drawn on [panel] (mockups: rgba(238,242,234,.45)). */
    val onPanelOutline: Color get() = onPanel.copy(alpha = 0.45f)

    /** Subtle outline on [panel] (track of the answer slider, rgba(238,242,234,.3)). */
    val onPanelFaint: Color get() = onPanel.copy(alpha = 0.3f)

    /** Unspoken transcript text / unplayed waveform on [panel]. */
    val onPanelDim: Color get() = onPanel.copy(alpha = 0.42f)
}

/** Raw palette. Only [LightColors] / [DarkColors] should reference these. */
internal object Palette {
    val Ground = Color(0xFFECEEE8)
    val Surface = Color(0xFFF7F8F4)
    val Ink = Color(0xFF1B2420)
    val Muted = Color(0xFF4F5A54)
    val Line = Color(0xFFC9CFC5)
    val Panel = Color(0xFF1F3B31)
    val OnPanel = Color(0xFFEEF2EA)
    val OnPanelMuted = Color(0xFFA9BBB1)
    val Lamp = Color(0xFFF2A900)
    val Missed = Color(0xFFB3321E)
    val NavBg = Color(0xFFE1E5DD)
    val Tint = Color(0xFFDDE2D8)
    val Focus = Color(0xFF2F6BD8)

    // Dark variant: derived from the panel's bottle green (hue ~155°), lowered in lightness so the
    // brand panel reads as the raised, lit surface on a darker exchange-room ground.
    val DarkGround = Color(0xFF111C18)
    val DarkSurface = Color(0xFF18261F)
    val DarkInk = Color(0xFFE6ECE3)
    val DarkMuted = Color(0xFF9DB0A6)
    val DarkLine = Color(0xFF2C3D35)
    val DarkMissed = Color(0xFFE0664F)
    val DarkNavBg = Color(0xFF15221C)
    val DarkTint = Color(0xFF22332B)
    val DarkFocus = Color(0xFF7FA6F0)
}

val LightColors = C2RColors(
    ground = Palette.Ground,
    surface = Palette.Surface,
    ink = Palette.Ink,
    muted = Palette.Muted,
    line = Palette.Line,
    panel = Palette.Panel,
    onPanel = Palette.OnPanel,
    onPanelMuted = Palette.OnPanelMuted,
    lamp = Palette.Lamp,
    onLamp = Palette.Ink,
    missed = Palette.Missed,
    navBg = Palette.NavBg,
    tint = Palette.Tint,
    focus = Palette.Focus,
    isDark = false,
)

val DarkColors = C2RColors(
    ground = Palette.DarkGround,
    surface = Palette.DarkSurface,
    ink = Palette.DarkInk,
    muted = Palette.DarkMuted,
    line = Palette.DarkLine,
    panel = Palette.Panel,
    onPanel = Palette.OnPanel,
    onPanelMuted = Palette.OnPanelMuted,
    lamp = Palette.Lamp,
    onLamp = Palette.Ink,
    missed = Palette.DarkMissed,
    navBg = Palette.DarkNavBg,
    tint = Palette.DarkTint,
    focus = Palette.DarkFocus,
    isDark = true,
)

val LocalC2RColors = staticCompositionLocalOf { LightColors }
