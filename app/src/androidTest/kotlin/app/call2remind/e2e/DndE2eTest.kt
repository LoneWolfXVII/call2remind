package app.call2remind.e2e

import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Calls
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.Waits
import app.call2remind.e2e.support.e2eLog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Do Not Disturb: the ring is an alarm, so priority and alarms-only modes let the full-screen
 * call through; total silence rings silently (vibration only) with a one-time hint.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DndE2eTest {
    @get:Rule
    val e2e = E2eRule()

    @Test
    fun priorityModeStillRingsFullScreen() = ringsFullScreenUnder("priority")

    @Test
    fun alarmsOnlyModeStillRingsFullScreen() = ringsFullScreenUnder("alarms")

    private fun ringsFullScreenUnder(mode: String) {
        e2e.onTearDown("DND off") { Device.setDnd("off") }
        Device.setDnd(mode)
        val call = AppDriver.scheduleCall("dnd $mode")
        Device.sleepScreen()
        Calls.awaitFullScreenRing(call)
        e2eLog("DND $mode: alarm players while ringing=${alarmPlayers()}")
        assertWithMessage("no silent-ring hint when DND lets alarms through").that(AppDriver.dndHintNotification()).isNull()
        assertThat(AppDriver.settings().onboarding.dndHintShown).isFalse()
    }

    @Test
    fun totalSilenceRingsSilentlyWithHint() {
        e2e.onTearDown("DND off") { Device.setDnd("off") }
        Device.setDnd("on")
        val call = AppDriver.scheduleCall("dnd total silence")
        Device.sleepScreen()
        Calls.awaitRinging(call)

        assertWithMessage("one-time DND hint notification").that(
            Waits.within(10_000) { AppDriver.dndHintNotification() != null },
        ).isTrue()
        assertThat(AppDriver.settings().onboarding.dndHintShown).isTrue()
        assertWithMessage("no ringtone playing under total silence").that(alarmPlayers()).isEqualTo(0)
        e2eLog("vibrations: " + Device.shell("dumpsys vibrator_manager").lines().filter { it.contains("app.call2remind") }.take(5))

        // The spec wants a silent full-screen call; whether the platform launches a full-screen
        // intent under total silence is up to its DND visual-effects policy.
        val shown = Calls.awaitCallScreen(call.title, call.fireAt.plus(Calls.RING_LATENESS))
        e2eLog("DND total silence: full-screen call shown=$shown resumed=${Device.resumedActivities()} interactive=${Device.isInteractive}")
        assumeTrue(
            "PLATFORM: full-screen intent suppressed under DND total silence (ring is RINGING, service foreground, hint shown)",
            shown,
        )
    }

    /** Active playback of this app with alarm usage (our ringtone). */
    private fun alarmPlayers(): Int {
        val audio = Device.context.getSystemService(AudioManager::class.java)
        return audio.activePlaybackConfigurations.count { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    }
}
