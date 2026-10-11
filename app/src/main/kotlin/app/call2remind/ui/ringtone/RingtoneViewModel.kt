package app.call2remind.ui.ringtone

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.SourceType
import app.call2remind.di.IoDispatcher
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the ringtone picker is choosing for. Encoded in the route as a string ([key]). */
sealed interface RingtoneTarget {
    val key: String

    /** Settings → Default ringtone. */
    data object Default : RingtoneTarget {
        override val key: String = "default"
    }

    /** Settings → a source's ringtone override (`null` = same as default). */
    data class Source(val type: SourceType) : RingtoneTarget {
        override val key: String get() = "source:${type.name}"
    }

    /** The habit editor's ringtone (returned to the editor; `null` = app default). */
    data object Habit : RingtoneTarget {
        override val key: String = "habit"
    }

    companion object {
        fun parse(key: String): RingtoneTarget = when {
            key == Habit.key -> Habit
            key.startsWith("source:") -> SourceType.entries.firstOrNull { it.name == key.removePrefix("source:") }?.let(::Source) ?: Default
            else -> Default
        }
    }
}

data class RingtoneUiState(
    val target: RingtoneTarget = RingtoneTarget.Default,
    /** The chosen uri; `null` = default (the phone's alarm sound, or "same as default"). */
    val selected: String? = null,
    val bundled: List<Tone> = emptyList(),
    /** `null` while loading. */
    val system: List<Tone>? = null,
    /** Uri previewing now ([DEFAULT_TONE_KEY] for the default sound), `null` when silent. */
    val playing: String? = null,
)

/**
 * Ringtone picker: our bundled tones and the phone's alarm + ringtone sounds. Tapping a row
 * selects it (Settings targets are saved at once; the habit target is handed back to the
 * editor); the play button previews it on the alarm stream. Previews stop after a few rings,
 * on leaving, and when another starts.
 */
@HiltViewModel
class RingtoneViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settings: SettingsRepository,
    private val catalog: RingtoneCatalog,
    private val player: TonePreviewPlayer,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val target: RingtoneTarget = RingtoneTarget.parse(savedStateHandle.get<String>(ARG_TARGET) ?: RingtoneTarget.Default.key)
    private val initial: String? = savedStateHandle.get<String>(ARG_CURRENT)?.ifEmpty { null }

    private val local = MutableStateFlow(RingtoneUiState(target = target, selected = initial, bundled = catalog.bundled()))
    private var autoStop: Job? = null

    val state: StateFlow<RingtoneUiState> = combine(local, settings.settings, player.playing) { l, s, playing ->
        val selected = when (val t = target) {
            RingtoneTarget.Default -> s.defaultRingtoneUri
            is RingtoneTarget.Source -> s.sourceRingtones[t.type]
            RingtoneTarget.Habit -> l.selected
        }
        l.copy(selected = selected, playing = playing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val system = withContext(io) { catalog.system() }
            local.value = local.value.copy(system = system)
        }
    }

    /** Selects [uri] (`null` = default). */
    fun select(uri: String?) {
        when (val t = target) {
            RingtoneTarget.Default -> viewModelScope.launch { settings.update { it.copy(defaultRingtoneUri = uri) } }
            is RingtoneTarget.Source -> viewModelScope.launch {
                settings.update { s ->
                    s.copy(sourceRingtones = if (uri == null) s.sourceRingtones - t.type else s.sourceRingtones + (t.type to uri))
                }
            }
            RingtoneTarget.Habit -> local.value = local.value.copy(selected = uri)
        }
    }

    /** Plays [uri] or, if it is the one playing, stops it. */
    fun togglePreview(uri: String?) {
        val key = uri ?: DEFAULT_TONE_KEY
        if (player.playing.value == key) {
            stopPreview()
            return
        }
        player.play(uri)
        autoStop?.cancel()
        autoStop = viewModelScope.launch {
            delay(PREVIEW_MS)
            player.stop()
        }
    }

    fun stopPreview() {
        autoStop?.cancel()
        player.stop()
    }

    override fun onCleared() {
        stopPreview()
    }

    companion object {
        const val ARG_TARGET = "target"
        const val ARG_CURRENT = "current"
        const val PREVIEW_MS = 8_000L
    }
}
