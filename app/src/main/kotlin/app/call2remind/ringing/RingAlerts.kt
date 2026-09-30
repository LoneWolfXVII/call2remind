package app.call2remind.ringing

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import app.call2remind.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Ringtone + vibration of a ringing call. Main thread only. */
interface RingAlerts {
    /**
     * Starts a looping ringtone (if [sound]) and a repeating vibration (if [vibrate]).
     * [ringtoneUri] `null` or unplayable → the system default alarm / ringtone sound, and as a
     * last resort the bundled ring ([bundledRingUri]). While the ringtone plays, transient audio
     * focus is held so other audio pauses.
     */
    fun start(ringtoneUri: String?, sound: Boolean, vibrate: Boolean)

    /** Stops everything started by [start]; safe to call repeatedly. */
    fun stop()
}

/** [RingAlerts] with [MediaPlayer] on `USAGE_ALARM` and the system vibrator. */
@Singleton
class AndroidRingAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
) : RingAlerts {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val focus = AlarmAudioFocus(context, AudioAttributes.CONTENT_TYPE_SONIFICATION)

    override fun start(ringtoneUri: String?, sound: Boolean, vibrate: Boolean) {
        stop()
        if (sound) {
            focus.request()
            playFrom(candidates(ringtoneUri), 0)
        }
        if (vibrate) startVibration()
    }

    override fun stop() {
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed", e)
            }
            mp.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        focus.abandon()
    }

    private fun candidates(ringtoneUri: String?): List<Uri> = listOfNotNull(
        ringtoneUri?.let(Uri::parse),
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
        Settings.System.DEFAULT_ALARM_ALERT_URI,
        Settings.System.DEFAULT_RINGTONE_URI,
        bundledRingUri(context),
    ).distinct()

    /** Tries [uris] from [index] on, moving to the next one on any failure. */
    private fun playFrom(uris: List<Uri>, index: Int) {
        if (index >= uris.size) return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(ALARM_ATTRIBUTES)
            mp.setDataSource(context, uris[index])
            mp.isLooping = true
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    failed.release()
                    player = null
                    playFrom(uris, index + 1)
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: IOException) {
            retry(mp, uris, index, e)
        } catch (e: IllegalArgumentException) {
            retry(mp, uris, index, e)
        } catch (e: IllegalStateException) {
            retry(mp, uris, index, e)
        } catch (e: SecurityException) {
            retry(mp, uris, index, e)
        }
    }

    private fun retry(mp: MediaPlayer, uris: List<Uri>, index: Int, error: Exception) {
        Log.w(TAG, "Cannot play ${uris[index]}", error)
        mp.release()
        if (player === mp) player = null
        playFrom(uris, index + 1)
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, ALARM_ATTRIBUTES)
        }
        vibrator = v
    }

    companion object {
        private const val TAG = "RingAlerts"

        /** The ring bundled with the app (`res/raw/c2r_fallback_ring`): plays when no system sound does. */
        fun bundledRingUri(context: Context): Uri =
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.c2r_fallback_ring}")

        private val VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)
        private val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
