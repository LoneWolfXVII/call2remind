package app.call2remind.ui.ringtone

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import app.call2remind.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A ringtone the picker can offer. [uri] is what gets stored in settings. */
data class Tone(val uri: String, val title: String, val description: String? = null)

/** The sounds a reminder can ring with: our bundled tones and the phone's own. */
interface RingtoneCatalog {
    /** Tones shipped with the app (Switchboard, Trunk line…), in display order. */
    fun bundled(): List<Tone>

    /** The phone's alarm and ringtone sounds (reads the media provider; call off the main thread). */
    fun system(): List<Tone>

    /**
     * Display title of a stored ringtone uri; `null` for `null` (the default alarm sound) or an
     * unknown / unreadable sound.
     */
    fun titleFor(uri: String?): String?
}

/** [TonePreviewPlayer.playing] while the default alarm sound (a `null` uri) previews. */
const val DEFAULT_TONE_KEY: String = "default"

/** Plays one ringtone at a time for previews; [playing] is the uri currently sounding. */
interface TonePreviewPlayer {
    val playing: StateFlow<String?>

    /** Plays [uri] (`null` = the default alarm sound), replacing any preview. */
    fun play(uri: String?)

    fun stop()
}

/** The bundled tones. Titles are resources so they translate. */
enum class BundledTone(@RawRes val raw: Int, @StringRes val title: Int, @StringRes val description: Int) {
    SWITCHBOARD(R.raw.c2r_switchboard, R.string.tone_switchboard, R.string.tone_switchboard_desc),
    TRUNK_LINE(R.raw.c2r_trunk_line, R.string.tone_trunk_line, R.string.tone_trunk_line_desc),
    DESK_BELL(R.raw.c2r_desk_bell, R.string.tone_desk_bell, R.string.tone_desk_bell_desc),
    ROTARY(R.raw.c2r_rotary, R.string.tone_rotary, R.string.tone_rotary_desc),
    SOFT_PULSE(R.raw.c2r_soft_pulse, R.string.tone_soft_pulse, R.string.tone_soft_pulse_desc),
    ;

    fun uri(packageName: String): String = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/raw/${rawName()}"

    /** Resource entry name, stable across builds (unlike the numeric id). */
    fun rawName(): String = when (this) {
        SWITCHBOARD -> "c2r_switchboard"
        TRUNK_LINE -> "c2r_trunk_line"
        DESK_BELL -> "c2r_desk_bell"
        ROTARY -> "c2r_rotary"
        SOFT_PULSE -> "c2r_soft_pulse"
    }
}

@Singleton
class AndroidRingtoneCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : RingtoneCatalog {

    override fun bundled(): List<Tone> = BundledTone.entries.map {
        Tone(it.uri(context.packageName), context.getString(it.title), context.getString(it.description))
    } + Tone(
        Settings.System.DEFAULT_RINGTONE_URI.toString(),
        context.getString(R.string.tone_phone_ringtone),
        context.getString(R.string.tone_phone_ringtone_desc),
    )

    override fun system(): List<Tone> {
        val tones = LinkedHashMap<String, Tone>()
        for (type in intArrayOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE)) {
            try {
                val manager = RingtoneManager(context).apply { setType(type) }
                val cursor = manager.cursor
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                    val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
                    tones.putIfAbsent(uri, Tone(uri, title))
                }
            } catch (e: RuntimeException) {
                // Some OEM media providers throw (SecurityException, IllegalStateException).
                Log.w(TAG, "Cannot list system sounds of type $type", e)
            }
        }
        return tones.values.sortedBy { it.title.lowercase() }
    }

    override fun titleFor(uri: String?): String? {
        if (uri == null) return null
        BundledTone.entries.firstOrNull { it.uri(context.packageName) == uri }?.let { return context.getString(it.title) }
        if (uri == Settings.System.DEFAULT_RINGTONE_URI.toString()) return context.getString(R.string.tone_phone_ringtone)
        return try {
            RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No title for $uri", e)
            null
        }
    }

    private companion object {
        const val TAG = "RingtoneCatalog"
    }
}

/** Previews on the alarm stream, like the real call, so the volume the user hears is the real one. */
@Singleton
class MediaTonePreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TonePreviewPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    override val playing: StateFlow<String?> = _playing.asStateFlow()

    override fun play(uri: String?) {
        stop()
        val target = uri?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(context, target)
            mp.isLooping = true
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            player = mp
            _playing.value = uri ?: DEFAULT_TONE_KEY
            mp.prepareAsync()
        } catch (e: IOException) {
            fail(mp, e)
        } catch (e: IllegalArgumentException) {
            fail(mp, e)
        } catch (e: IllegalStateException) {
            fail(mp, e)
        } catch (e: SecurityException) {
            fail(mp, e)
        }
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
        _playing.value = null
    }

    private fun fail(mp: MediaPlayer, e: Exception) {
        Log.w(TAG, "Cannot preview", e)
        mp.release()
        if (player === mp) player = null
        _playing.value = null
    }

    private companion object {
        const val TAG = "TonePreview"
    }
}
