package app.call2remind.ringing

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import app.call2remind.core.ringing.RingContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the device conditions that decide how a ring is presented. */
interface RingContextProvider {
    fun current(): RingContext
}

/**
 * Reads the ring context from system services. No READ_PHONE_STATE: "in a real call" is derived
 * from [AudioManager.getMode] (any mode other than NORMAL means telephony/VoIP audio is active:
 * ringing, in call, in communication, call screening…).
 */
@Singleton
class AndroidRingContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundTracker: AppForegroundTracker,
) : RingContextProvider {

    private val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val notificationManager = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java))

    override fun current(): RingContext = RingContext(
        inRealCall = isInCallMode(audioManager.mode),
        dndTotalSilence = notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE,
        appInForeground = foregroundTracker.isInForeground,
        canUseFullScreenIntent = canUseFullScreenIntent(),
        screenInteractive = powerManager.isInteractive,
    )

    private fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }

    companion object {
        /** True if the audio [mode] indicates a phone or VoIP call (ringing or active). */
        fun isInCallMode(mode: Int): Boolean = mode != AudioManager.MODE_NORMAL && mode >= 0
    }
}
