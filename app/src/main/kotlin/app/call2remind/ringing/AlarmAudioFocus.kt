package app.call2remind.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`) on the alarm stream, held while the ring
 * plays or the reminder is spoken, so music / podcasts pause instead of playing over the call.
 * Losing focus is ignored: a ringing reminder keeps ringing. Thread-safe; [abandon] is idempotent.
 */
class AlarmAudioFocus(context: Context, contentType: Int) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(contentType)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var requested = false

    /** Requests focus; returns true if granted. Playback proceeds either way. */
    @Synchronized
    fun request(): Boolean {
        val manager = audioManager ?: return false
        requested = true
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Gives focus back if [request] was called since the last abandon. */
    @Synchronized
    fun abandon() {
        if (!requested) return
        requested = false
        audioManager?.abandonAudioFocusRequest(request)
    }
}
