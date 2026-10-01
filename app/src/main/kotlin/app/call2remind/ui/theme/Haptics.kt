package app.call2remind.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** The three haptic moments the spec pairs with motion. */
enum class Haptic {
    /** A detent while dragging (25 / 50 / 75 % of the answer slider, toggles). */
    SegmentTick,

    /** Something committed (call answered, snooze set). */
    Confirm,

    /** Something refused or dismissed (decline). */
    Reject,
}

/** Performs [Haptic]s on a [View], using the closest constant the running API level has. */
@Stable
class C2RHaptics(private val view: View) {
    fun perform(haptic: Haptic) {
        view.performHapticFeedback(constantFor(haptic, Build.VERSION.SDK_INT))
    }

    companion object {
        /** The [HapticFeedbackConstants] value for [haptic] on API [sdk] (constants are inlined ints). */
        @Suppress("InlinedApi")
        fun constantFor(haptic: Haptic, sdk: Int): Int = when (haptic) {
            Haptic.SegmentTick ->
                if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }
}

@Composable
fun rememberHaptics(): C2RHaptics {
    val view = LocalView.current
    return remember(view) { C2RHaptics(view) }
}
