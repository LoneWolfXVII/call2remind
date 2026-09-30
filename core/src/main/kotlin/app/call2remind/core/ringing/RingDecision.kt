package app.call2remind.core.ringing

/** How a due occurrence is presented. */
enum class RingMode {
    /** Full-screen incoming-call activity via full-screen intent, with sound + vibration. */
    FULL_SCREEN,

    /** The app is visible: show the call as an in-app overlay. */
    IN_APP_OVERLAY,

    /** Full-screen intents are not allowed: heads-up CallStyle notification + ringtone from the FGS. */
    HEADS_UP_DEGRADED,

    /** DND total silence: full-screen call UI, vibration only, no sound (plus one-time hint). */
    SILENT_FULL_SCREEN_VIBRATE,

    /** The user is in a real phone call: silent heads-up now, ring when the call ends. */
    DEFER_UNTIL_CALL_ENDS,
}

/**
 * Device conditions at ring time.
 *
 * @property dndTotalSilence Do Not Disturb silences alarms (total silence, or priority mode with
 * alarms excluded). Alarms-only and default priority mode let our ring (an alarm) through.
 * @property screenInteractive only qualifies [appInForeground]: an overlay is used only when the
 * screen is on, since an overlay cannot turn the screen on.
 */
data class RingContext(
    val inRealCall: Boolean,
    val dndTotalSilence: Boolean,
    val appInForeground: Boolean,
    val canUseFullScreenIntent: Boolean,
    val screenInteractive: Boolean,
)

/**
 * Chooses a [RingMode]. Precedence, first match wins:
 *
 * 1. `inRealCall` → [RingMode.DEFER_UNTIL_CALL_ENDS] (never interrupt a real call).
 * 2. `appInForeground && screenInteractive` → [RingMode.IN_APP_OVERLAY].
 * 3. `!canUseFullScreenIntent` → [RingMode.HEADS_UP_DEGRADED].
 * 4. `dndTotalSilence` → [RingMode.SILENT_FULL_SCREEN_VIBRATE].
 * 5. otherwise → [RingMode.FULL_SCREEN].
 *
 * Sound is decided separately by [soundAllowed] so DND is honoured in every mode.
 */
object RingDecision {

    /** The presentation mode for [context]. */
    fun decide(context: RingContext): RingMode = when {
        context.inRealCall -> RingMode.DEFER_UNTIL_CALL_ENDS
        context.appInForeground && context.screenInteractive -> RingMode.IN_APP_OVERLAY
        !context.canUseFullScreenIntent -> RingMode.HEADS_UP_DEGRADED
        context.dndTotalSilence -> RingMode.SILENT_FULL_SCREEN_VIBRATE
        else -> RingMode.FULL_SCREEN
    }

    /** Whether the ringtone may play: never during a real call or DND total silence. */
    fun soundAllowed(context: RingContext): Boolean = !context.inRealCall && !context.dndTotalSilence

    /** Whether vibration may be used: everywhere except during a real call. */
    fun vibrationAllowed(context: RingContext): Boolean = !context.inRealCall
}
