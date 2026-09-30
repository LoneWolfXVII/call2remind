package app.call2remind.ringing

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.VibratorManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
class RingAlertsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val alerts = AndroidRingAlerts(context)
    private val tone = Uri.parse("content://media/internal/audio/media/7")

    @After
    fun tearDown() = alerts.stop()

    /** The current player (private state; read reflectively to inspect the shadow). */
    private fun player(): MediaPlayer? =
        AndroidRingAlerts::class.java.getDeclaredField("player").apply { isAccessible = true }.get(alerts) as MediaPlayer?

    private fun playable(uri: Uri) =
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))

    @Test
    fun playsTheRingtoneLoopingOnTheAlarmStream() {
        playable(tone)

        alerts.start(tone.toString(), sound = true, vibrate = false)
        shadowOf(Looper.getMainLooper()).idle()

        val mp = requireNotNull(player())
        val shadow = shadowOf(mp)
        assertThat(shadow.state).isEqualTo(ShadowMediaPlayer.State.STARTED)
        assertThat(mp.isLooping).isTrue()
        assertThat(shadow.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(shadow.dataSource).isEqualTo(DataSource.toDataSource(context, tone))
        assertThat(shadowOf(vibrator).isVibrating).isFalse()
    }

    @Test
    fun unplayableRingtoneFallsBackToTheSystemAlarmSound() {
        playable(Settings.System.DEFAULT_ALARM_ALERT_URI)

        alerts.start("content://nope/1", sound = true, vibrate = false)
        shadowOf(Looper.getMainLooper()).idle()

        val shadow = shadowOf(requireNotNull(player()))
        assertThat(shadow.dataSource).isEqualTo(DataSource.toDataSource(context, Settings.System.DEFAULT_ALARM_ALERT_URI))
        assertThat(shadow.state).isEqualTo(ShadowMediaPlayer.State.STARTED)
    }

    @Test
    fun nothingPlayableStaysSilentWithoutCrashing() {
        alerts.start(null, sound = true, vibrate = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(player()).isNull()
        assertThat(shadowOf(vibrator).isVibrating).isTrue()
    }

    @Test
    fun vibrationWithoutSoundAndStopCancelsEverything() {
        playable(tone)
        alerts.start(tone.toString(), sound = false, vibrate = true)
        assertThat(player()).isNull()
        assertThat(shadowOf(vibrator).isVibrating).isTrue()

        alerts.start(tone.toString(), sound = true, vibrate = true)
        shadowOf(Looper.getMainLooper()).idle()
        val mp = requireNotNull(player())

        alerts.stop()
        alerts.stop()

        assertThat(player()).isNull()
        assertThat(shadowOf(mp).state).isEqualTo(ShadowMediaPlayer.State.END)
        assertThat(shadowOf(vibrator).isCancelled).isTrue()
    }
}
