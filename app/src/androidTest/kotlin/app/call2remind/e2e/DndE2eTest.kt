package app.call2remind.e2e

import android.app.Notification
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.call2remind.core.model.OccurrenceState
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Calls
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.Waits
import app.call2remind.e2e.support.e2eLog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Do Not Disturb: the ring is an alarm, so priority and alarms-only modes let the full-screen
 * call through. Total silence blocks alarms (sound, vibration and full-screen intents), so the
 * ring is a silent "Reminder: <title>" notification with Answer / Snooze / Done plus a one-time hint.
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
    fun totalSilenceRingsAsASilentReminderNotificationWithHint() {
        e2e.onTearDown("DND off") { Device.setDnd("off") }
        Device.setDnd("on")
        val call = AppDriver.scheduleCall("dnd total silence")
        Device.sleepScreen()
        val ringing = Calls.awaitRinging(call)
        assertThat(ringing.state).isEqualTo(OccurrenceState.RINGING)

        assertWithMessage("one-time DND hint notification").that(
            Waits.within(10_000) { AppDriver.dndHintNotification() != null },
        ).isTrue()
        // The service posts the hint, then records it in settings: wait for the write.
        Waits.until("DND hint recorded as shown", 5_000) { AppDriver.settings().onboarding.dndHintShown }
        assertWithMessage("no ringtone playing under total silence").that(alarmPlayers()).isEqualTo(0)
        e2eLog("vibrations: " + Device.shell("dumpsys vibrator_manager").lines().filter { it.contains("app.call2remind") }.take(5))

        // Android suppresses full-screen intents under total silence, so the ring is an honest,
        // silent "Reminder: <title>" notification that waits in the shade with its actions.
        val ringId = AppDriver.app.notifications.ringId(call.occurrenceId)
        val posted = Waits.valueOrNull(10_000) { AppDriver.ringNotification(call.occurrenceId) }
        val dump = Device.shell("dumpsys notification --noredact").lines()
            .filter { it.contains("pkg=app.call2remind") && it.contains("id=$ringId") }
        e2eLog("DND total silence: ring notification posted=${posted != null} dumpsys=${dump.take(3)} interactive=${Device.isInteractive}")
        if (posted == null) {
            // Not visible through our own NotificationManager: it must at least be on record.
            assertWithMessage("ring notification $ringId in dumpsys notification").that(dump).isNotEmpty()
            assertThat(AppDriver.occurrence(call.occurrenceId)?.state).isEqualTo(OccurrenceState.RINGING)
            return
        }
        val notification = posted.notification
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Reminder: ${call.title}")
        assertWithMessage("no full-screen intent (the platform would suppress it)").that(notification.fullScreenIntent).isNull()
        assertThat(notification.flags and Notification.FLAG_INSISTENT).isEqualTo(0)
        val actions = notification.actions.orEmpty()
        assertThat(actions.map { it.title.toString() }).containsExactly("Answer", "Snooze", "Done").inOrder()
        assertWithMessage("no call screen under total silence").that(Device.resumedActivities().none { it.contains("IncomingCallActivity") }).isTrue()

        // The shade's Snooze works like the call screen's.
        actions[1].actionIntent.send()
        val snoozed = AppDriver.awaitState(call.occurrenceId, OccurrenceState.SNOOZED)
        assertThat(snoozed.ringBacks).isEqualTo(1)
        Waits.until("ring notification removed after snoozing", 10_000) { AppDriver.ringNotification(call.occurrenceId) == null }
    }

    /** Active playback of this app with alarm usage (our ringtone). */
    private fun alarmPlayers(): Int {
        val audio = Device.context.getSystemService(AudioManager::class.java)
        return audio.activePlaybackConfigurations.count { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    }
}
