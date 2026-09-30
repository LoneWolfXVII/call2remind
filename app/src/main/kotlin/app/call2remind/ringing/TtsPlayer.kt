package app.call2remind.ringing

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Progress of an utterance; [Range] drives word highlighting in the answered-call UI. */
sealed interface TtsEvent {
    val utteranceId: String

    data class Started(override val utteranceId: String) : TtsEvent

    /** Characters [start, end) of the text are about to be spoken. */
    data class Range(override val utteranceId: String, val start: Int, val end: Int) : TtsEvent

    data class Done(override val utteranceId: String) : TtsEvent

    data class Stopped(override val utteranceId: String) : TtsEvent

    data class Error(override val utteranceId: String, val code: Int) : TtsEvent
}

/** Text-to-speech for the answered call. */
interface TtsPlayer {
    /** Utterance progress of everything spoken through this player. */
    val events: SharedFlow<TtsEvent>

    /** Speaks [text] (flushing anything queued) and suspends until done. True if fully spoken. */
    suspend fun speak(text: String): Boolean

    /** Stops speaking now. */
    fun stop()
}

/** [TtsPlayer] on [TextToSpeech]; the engine is created lazily and kept for the process. */
@Singleton
class AndroidTtsPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsPlayer {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private val initLock = Mutex()
    private var engine: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _events.tryEmit(TtsEvent.Started(utteranceId ?: return))
        }

        override fun onDone(utteranceId: String?) {
            _events.tryEmit(TtsEvent.Done(utteranceId ?: return))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _events.tryEmit(TtsEvent.Error(utteranceId ?: return, TextToSpeech.ERROR))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _events.tryEmit(TtsEvent.Error(utteranceId ?: return, errorCode))
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _events.tryEmit(TtsEvent.Stopped(utteranceId ?: return))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            _events.tryEmit(TtsEvent.Range(utteranceId ?: return, start, end))
        }
    }

    override suspend fun speak(text: String): Boolean {
        val tts = engine() ?: return false
        val utteranceId = UUID.randomUUID().toString()
        return coroutineScope {
            val finished = async(start = CoroutineStart.UNDISPATCHED) {
                _events.first { it.utteranceId == utteranceId && it.isTerminal() }
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) != TextToSpeech.SUCCESS) {
                finished.cancel()
                false
            } else {
                finished.await() is TtsEvent.Done
            }
        }
    }

    override fun stop() {
        engine?.stop()
    }

    private suspend fun engine(): TextToSpeech? = initLock.withLock {
        engine ?: withContext(Dispatchers.Main) {
            withTimeoutOrNull(INIT_TIMEOUT_MS) { create() }
        }?.also { engine = it }
    }

    private suspend fun create(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val created = tts
            if (status == TextToSpeech.SUCCESS && created != null) {
                created.setOnUtteranceProgressListener(listener)
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                if (cont.isActive) cont.resume(created)
            } else {
                created?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { tts?.shutdown() }
    }

    private fun TtsEvent.isTerminal(): Boolean = this is TtsEvent.Done || this is TtsEvent.Stopped || this is TtsEvent.Error

    private companion object {
        const val EVENT_BUFFER = 64
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
