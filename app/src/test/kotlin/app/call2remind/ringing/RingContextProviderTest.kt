package app.call2remind.ringing

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.ringing.RingContext
import app.call2remind.core.ringing.RingDecision
import app.call2remind.core.ringing.RingMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// API 33: NotificationManager.canUseFullScreenIntent (34+) has no Robolectric shadow.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RingContextProviderTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val tracker = AppForegroundTracker()
    private val provider = AndroidRingContextProvider(context, tracker)

    private fun newActivity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun idleDeviceRingsFullScreenWithSound() {
        val ringContext = provider.current()

        assertThat(ringContext).isEqualTo(
            RingContext(
                inRealCall = false,
                dndTotalSilence = false,
                appInForeground = false,
                canUseFullScreenIntent = true,
                screenInteractive = true,
            ),
        )
        assertThat(RingDecision.decide(ringContext)).isEqualTo(RingMode.FULL_SCREEN)
        assertThat(RingDecision.soundAllowed(ringContext)).isTrue()
    }

    @Test
    fun anyNonNormalAudioModeMeansARealCall() {
        for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) {
            audio.mode = mode
            assertThat(provider.current().inRealCall).isTrue()
        }
        audio.mode = AudioManager.MODE_NORMAL
        assertThat(provider.current().inRealCall).isFalse()
    }

    @Test
    fun inCallModeClassification() {
        assertThat(AndroidRingContextProvider.isInCallMode(AudioManager.MODE_NORMAL)).isFalse()
        assertThat(AndroidRingContextProvider.isInCallMode(AudioManager.MODE_CURRENT)).isFalse()
        assertThat(AndroidRingContextProvider.isInCallMode(AudioManager.MODE_INVALID)).isFalse()
        assertThat(AndroidRingContextProvider.isInCallMode(AudioManager.MODE_IN_CALL)).isTrue()
        assertThat(AndroidRingContextProvider.isInCallMode(AudioManager.MODE_CALL_SCREENING)).isTrue()
    }

    @Test
    fun dndSilencesTheRingOnlyWhenItSilencesAlarms() {
        // Alarms-only mode lets alarms (our ring) through.
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        assertThat(provider.current().dndTotalSilence).isFalse()
        assertThat(RingDecision.decide(provider.current())).isEqualTo(RingMode.FULL_SCREEN)

        // Priority mode: alarms are allowed by default (no policy / policy allowing alarms)…
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertThat(provider.current().dndTotalSilence).isFalse()
        notifications.notificationPolicy = NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0)
        assertThat(provider.current().dndTotalSilence).isFalse()
        // …but a priority policy that excludes alarms silences us like total silence.
        notifications.notificationPolicy = NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_CALLS, 0, 0)
        assertThat(provider.current().dndTotalSilence).isTrue()
        assertThat(RingDecision.decide(provider.current())).isEqualTo(RingMode.DND_SILENT_NOTIFICATION)

        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertThat(provider.current().dndTotalSilence).isFalse()

        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val ringContext = provider.current()
        assertThat(ringContext.dndTotalSilence).isTrue()
        assertThat(RingDecision.decide(ringContext)).isEqualTo(RingMode.DND_SILENT_NOTIFICATION)
    }

    @Test
    fun alarmsSilencedClassification() {
        val alarms = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_NONE, alarms)).isTrue()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_ALARMS, 0)).isFalse()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_PRIORITY, null)).isFalse()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarms)).isFalse()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_PRIORITY, 0)).isTrue()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_ALL, 0)).isFalse()
        assertThat(AndroidRingContextProvider.alarmsSilenced(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, 0)).isFalse()
    }

    @Test
    fun foregroundAndScreenStateAreReported() {
        tracker.onActivityStarted(newActivity())
        assertThat(provider.current().appInForeground).isTrue()

        shadowOf(power).setIsInteractive(false)
        val ringContext = provider.current()
        assertThat(ringContext.screenInteractive).isFalse()
        // An overlay cannot turn the screen on, so a dark screen still gets the full-screen call.
        assertThat(RingDecision.decide(ringContext)).isEqualTo(RingMode.FULL_SCREEN)
    }

    @Test
    fun foregroundTrackerCountsStartedActivities() {
        val first = newActivity()
        val second = newActivity()

        tracker.onActivityStarted(first)
        tracker.onActivityStarted(second)
        tracker.onActivityStopped(first)
        assertThat(tracker.isInForeground).isTrue()
        tracker.onActivityStopped(second)
        assertThat(tracker.isInForeground).isFalse()
        tracker.onActivityStopped(second)
        assertThat(tracker.inForeground.value).isFalse()
        tracker.onActivityStarted(first)
        assertThat(tracker.inForeground.value).isTrue()
    }
}
