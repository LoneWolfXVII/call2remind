@file:OptIn(ExperimentalTextApi::class)

package app.call2remind.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.call2remind.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val uiWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
private val monoWeights = listOf(FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

private fun downloadable(name: String, weights: List<FontWeight>, fallback: String): FontFamily {
    val font = GoogleFont(name)
    // Each weight: the downloadable font first, then a device font of the same weight. The resolver
    // renders the device font until the download lands (or forever if Play services is missing).
    return FontFamily(
        weights.flatMap { weight ->
            listOf(
                Font(googleFont = font, fontProvider = provider, weight = weight),
                Font(familyName = DeviceFontFamilyName(fallback), weight = weight),
            )
        },
    )
}

/** Schibsted Grotesk: every piece of UI text. */
val SchibstedGrotesk: FontFamily = downloadable("Schibsted Grotesk", uiWeights, "sans-serif")

/** JetBrains Mono: times and counters only (tabular by design). */
val JetBrainsMono: FontFamily = downloadable("JetBrains Mono", monoWeights, "monospace")

private val tightLines = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)
private val noPadding = PlatformTextStyle(includeFontPadding = false)

private fun ui(
    size: Int,
    weight: FontWeight,
    lineHeight: Float,
    tracking: TextUnit = 0.em,
): TextStyle = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
    lineHeightStyle = tightLines,
    platformStyle = noPadding,
)

private fun mono(size: Int, tracking: TextUnit = 0.02.em): TextStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = size.sp,
    fontWeight = FontWeight.ExtraBold,
    lineHeight = size.sp,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
    lineHeightStyle = tightLines,
    platformStyle = noPadding,
)

/**
 * The type scale, lifted from the mockups. UI roles use Schibsted Grotesk with the tracking and
 * leading the designs specify; `mono*` roles are JetBrains Mono for times (tabular figures).
 */
@Immutable
data class C2RTypography(
    /** Welcome headline, 46/0.98, -0.035em. */
    val display: TextStyle = ui(46, FontWeight.ExtraBold, 0.98f, (-0.035).em),
    /** Caller name on the incoming call, 40/1.04, -0.03em. */
    val callTitle: TextStyle = ui(40, FontWeight.ExtraBold, 1.04f, (-0.03).em),
    /** Caller name once answered, 32/1.08. */
    val callTitleSmall: TextStyle = ui(32, FontWeight.ExtraBold, 1.08f, (-0.025).em),
    /** Onboarding step headline, 30/1.1. */
    val headline: TextStyle = ui(30, FontWeight.ExtraBold, 1.1f, (-0.025).em),
    /** Top-level screen title ("Up next", "Settings"), 30/1.2. */
    val screenTitle: TextStyle = ui(30, FontWeight.Bold, 1.2f, (-0.01).em),
    /** Bottom sheet title ("Ring again in"), 26. */
    val sheetTitle: TextStyle = ui(26, FontWeight.ExtraBold, 1.15f, (-0.02).em),
    /** Transcript read aloud, 24/1.38. */
    val transcript: TextStyle = ui(24, FontWeight.Medium, 1.38f, (-0.01).em),
    /** Card title on a caller-ID strip, 22/1.2. */
    val cardTitle: TextStyle = ui(22, FontWeight.Bold, 1.2f, (-0.01).em),
    /** Chip value ("10 min"), 20. */
    val chipValue: TextStyle = ui(20, FontWeight.Bold, 1.2f, (-0.01).em),
    /** Lead paragraph, 17/1.5. */
    val lead: TextStyle = ui(17, FontWeight.Normal, 1.5f),
    /** Body copy, 16/1.5. */
    val body: TextStyle = ui(16, FontWeight.Normal, 1.5f),
    /** Row title in lists, 16/1.3 semibold. */
    val rowTitle: TextStyle = ui(16, FontWeight.SemiBold, 1.3f),
    /** Button label, 16 semibold, +0.005em. */
    val button: TextStyle = ui(16, FontWeight.SemiBold, 1.2f, 0.005.em),
    /** Section header, 15 bold. */
    val section: TextStyle = ui(15, FontWeight.Bold, 1.3f),
    /** Secondary lines, 14/1.4. */
    val caption: TextStyle = ui(14, FontWeight.Normal, 1.4f),
    /** Fine print, 13/1.45. */
    val footnote: TextStyle = ui(13, FontWeight.Normal, 1.45f),
    /** Bottom navigation label, 12/16. */
    val navLabel: TextStyle = ui(12, FontWeight.Medium, 1.34f),
    val monoHero: TextStyle = mono(96),
    val monoCall: TextStyle = mono(64),
    val monoLarge: TextStyle = mono(48),
    val monoTimer: TextStyle = mono(28, (-0.02).em),
    val monoSmall: TextStyle = mono(15, 0.em),
)

val LocalC2RTypography = staticCompositionLocalOf { C2RTypography() }
