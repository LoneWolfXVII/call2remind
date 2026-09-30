package app.call2remind.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.call2remind.ui.components.C2RIcons
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
class ThemeTokensTest {

    @Test
    fun lightTokensMatchTheDesignFiles() {
        with(LightColors) {
            assertThat(ground).isEqualTo(Color(0xFFECEEE8))
            assertThat(surface).isEqualTo(Color(0xFFF7F8F4))
            assertThat(ink).isEqualTo(Color(0xFF1B2420))
            assertThat(muted).isEqualTo(Color(0xFF4F5A54))
            assertThat(line).isEqualTo(Color(0xFFC9CFC5))
            assertThat(panel).isEqualTo(Color(0xFF1F3B31))
            assertThat(onPanel).isEqualTo(Color(0xFFEEF2EA))
            assertThat(onPanelMuted).isEqualTo(Color(0xFFA9BBB1))
            assertThat(lamp).isEqualTo(Color(0xFFF2A900))
            assertThat(missed).isEqualTo(Color(0xFFB3321E))
            assertThat(navBg).isEqualTo(Color(0xFFE1E5DD))
            assertThat(tint).isEqualTo(Color(0xFFDDE2D8))
        }
    }

    @Test
    fun lampIsAlwaysAFillBehindDarkText() {
        for (colors in listOf(LightColors, DarkColors)) {
            assertThat(contrast(colors.lamp, colors.onLamp)).isAtLeast(4.5)
        }
    }

    @Test
    fun darkThemeIsPanelBasedAndReadable() {
        with(DarkColors) {
            assertThat(isDark).isTrue()
            assertThat(panel).isEqualTo(LightColors.panel)
            assertThat(ground.luminance()).isLessThan(panel.luminance())
            assertThat(contrast(ink, ground)).isAtLeast(7.0)
            assertThat(contrast(muted, ground)).isAtLeast(4.5)
            assertThat(contrast(missed, ground)).isAtLeast(4.5)
            // Primary buttons are ink-on-surface in both themes.
            assertThat(contrast(surface, ink)).isAtLeast(7.0)
        }
        with(LightColors) {
            assertThat(contrast(ink, ground)).isAtLeast(7.0)
            assertThat(contrast(muted, ground)).isAtLeast(4.5)
            assertThat(contrast(onPanel, panel)).isAtLeast(7.0)
            assertThat(contrast(onPanelMuted, panel)).isAtLeast(4.5)
        }
    }

    @Test
    fun typeScaleUsesTheDesignSizes() {
        val type = C2RTypography()
        assertThat(type.display.fontSize).isEqualTo(46.sp)
        assertThat(type.callTitle.fontSize).isEqualTo(40.sp)
        assertThat(type.headline.fontSize).isEqualTo(30.sp)
        assertThat(type.monoCall.fontSize).isEqualTo(64.sp)
        assertThat(type.monoHero.fontSize).isEqualTo(96.sp)
        assertThat(type.monoCall.fontFamily).isEqualTo(JetBrainsMono)
        assertThat(type.body.fontFamily).isEqualTo(SchibstedGrotesk)
        assertThat(type.monoCall.fontWeight).isEqualTo(FontWeight.ExtraBold)
        assertThat(type.monoCall.fontFeatureSettings).isEqualTo("tnum")
    }

    @Test
    fun motionTokensAreSpringsUnlessAnimationsAreOff() {
        val motion = C2RMotion(reduced = false)
        val default = motion.default<Float>() as SpringSpec<Float>
        assertThat(default.dampingRatio).isEqualTo(0.7f)
        assertThat((motion.playful<Float>() as SpringSpec<Float>).dampingRatio).isEqualTo(0.5f)
        assertThat((motion.precise<Float>() as SpringSpec<Float>).dampingRatio).isEqualTo(1f)

        val reduced = C2RMotion(reduced = true)
        assertThat(reduced.default<Float>()).isInstanceOf(SnapSpec::class.java)
        assertThat(reduced.playful<Float>()).isInstanceOf(SnapSpec::class.java)
        assertThat(reduced.fade<Float>()).isInstanceOf(TweenSpec::class.java)
    }

    @Test
    fun hapticsFallBackOnOlderApis() {
        assertThat(C2RHaptics.constantFor(Haptic.SegmentTick, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
            .isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(C2RHaptics.constantFor(Haptic.SegmentTick, Build.VERSION_CODES.TIRAMISU))
            .isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(C2RHaptics.constantFor(Haptic.Confirm, Build.VERSION_CODES.R)).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(C2RHaptics.constantFor(Haptic.Confirm, Build.VERSION_CODES.Q)).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(C2RHaptics.constantFor(Haptic.Reject, Build.VERSION_CODES.R)).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(C2RHaptics.constantFor(Haptic.Reject, Build.VERSION_CODES.O)).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun everyIconParses() {
        for (icon in C2RIcons.all) {
            assertThat(icon.viewportWidth).isEqualTo(24f)
            assertThat(icon.root.size).isAtLeast(1)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return max(la, lb) / min(la, lb).toDouble()
    }
}
