package app.call2remind.ui.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Reminder
import app.call2remind.core.speech.SpeechText
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.ringing.IncomingCallActivity
import app.call2remind.ringing.TtsEvent
import app.call2remind.ringing.TtsPlayer
import app.call2remind.settings.Settings
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * What the call screen shows beyond [app.call2remind.ringing.CallUiState]: snooze allowance,
 * ring-backs, the sentence being read aloud.
 */
data class CallPresentation(
    val ringBacks: Int = 0,
    val maxRingBacks: Int = Settings.DEFAULT_MAX_RING_BACKS,
    /** `SnoozePolicy.canSnooze`: show snooze options only when true. */
    val canSnooze: Boolean = true,
    val defaultSnooze: Duration = Settings.DEFAULT_SNOOZE,
    /** The text TTS reads after answering; `null` while loading or when the voice is off. */
    val spokenText: String? = null,
    /** The transcript to show (same as [spokenText], but also present when the voice is off). */
    val transcript: String = "",
    val answeredAt: Instant? = null,
    /** When this ring was originally planned (the caller-ID time, also for ring-backs). */
    val plannedAt: Instant? = null,
) {
    val ringBacksLeft: Int get() = (maxRingBacks - ringBacks).coerceAtLeast(0)
}

/**
 * TTS progress of the current utterance.
 *
 * @property rangeExposed the engine reported word ranges ([TtsEvent.Range]); when false the UI
 * estimates the highlight from elapsed time.
 * @property spokenUntil end of the last reported range (characters), or the text length when done.
 */
data class SpeechProgress(
    val utteranceId: String? = null,
    val speaking: Boolean = false,
    val finished: Boolean = false,
    val rangeExposed: Boolean = false,
    val spokenUntil: Int = 0,
    /** Bumped every time a new utterance starts (restarts the estimate). */
    val generation: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CallPresentationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val occurrences: OccurrenceRepository,
    private val reminders: ReminderRepository,
    private val settings: SettingsRepository,
    private val tts: TtsPlayer,
) : ViewModel() {

    private val occurrenceId = MutableStateFlow(savedStateHandle.get<String>(IncomingCallActivity.EXTRA_OCCURRENCE_ID))

    private val _speech = MutableStateFlow(SpeechProgress())
    val speech: StateFlow<SpeechProgress> = _speech.asStateFlow()

    private var readAgainJob: Job? = null

    val presentation: StateFlow<CallPresentation> = occurrenceId.filterNotNull()
        .flatMapLatest { id ->
            occurrences.observe(id).flatMapLatest { occurrence ->
                if (occurrence == null) {
                    flowOf(CallPresentation())
                } else {
                    combine(reminders.observe(occurrence.reminderId), settings.settings) { reminder, current ->
                        present(occurrence, reminder, current)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CallPresentation())

    init {
        viewModelScope.launch { tts.events.collect(::onTtsEvent) }
    }

    fun bind(id: String) {
        if (occurrenceId.value != id) {
            occurrenceId.value = id
            _speech.value = SpeechProgress()
        }
    }

    /** "Read it again": speaks the transcript from the start. */
    fun readAgain() {
        val text = presentation.value.transcript.takeIf { it.isNotBlank() } ?: return
        readAgainJob?.cancel()
        readAgainJob = viewModelScope.launch { tts.speak(text) }
    }

    /** "Stop voice". */
    fun stopVoice() {
        readAgainJob?.cancel()
        tts.stop()
        _speech.update { it.copy(speaking = false) }
    }

    private fun onTtsEvent(event: TtsEvent) {
        _speech.update { current ->
            // The ringing service may start speaking before this screen subscribes, so adopt
            // whichever utterance we hear about; a Started always begins a new one.
            val sameUtterance = current.utteranceId == event.utteranceId
            when (event) {
                is TtsEvent.Started -> SpeechProgress(
                    utteranceId = event.utteranceId,
                    speaking = true,
                    generation = current.generation + 1,
                )
                is TtsEvent.Range -> if (sameUtterance || current.utteranceId == null || !current.speaking) {
                    current.copy(
                        utteranceId = event.utteranceId,
                        speaking = true,
                        finished = false,
                        rangeExposed = true,
                        spokenUntil = maxOf(if (sameUtterance) current.spokenUntil else 0, event.end),
                        generation = if (sameUtterance) current.generation else current.generation + 1,
                    )
                } else {
                    current
                }
                is TtsEvent.Done -> if (sameUtterance || current.utteranceId == null) {
                    current.copy(utteranceId = event.utteranceId, speaking = false, finished = true, spokenUntil = Int.MAX_VALUE)
                } else {
                    current
                }
                is TtsEvent.Stopped, is TtsEvent.Error -> if (sameUtterance) current.copy(speaking = false) else current
            }
        }
    }

    private fun present(occurrence: Occurrence, reminder: Reminder?, current: Settings): CallPresentation {
        val policy = current.snoozePolicy
        val transcript = reminder?.let { SpeechText.build(it, occurrence, ZoneId.systemDefault()) }.orEmpty()
        val voiceOn = reminder != null && reminder.ttsEnabled && current.ttsEnabled
        return CallPresentation(
            ringBacks = occurrence.ringBacks,
            maxRingBacks = policy.maxRingBacks,
            canSnooze = policy.canSnooze(occurrence),
            defaultSnooze = policy.defaultSnooze,
            spokenText = transcript.takeIf { voiceOn && it.isNotBlank() },
            transcript = transcript,
            answeredAt = occurrence.answeredAt,
            plannedAt = occurrence.plannedAt,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
