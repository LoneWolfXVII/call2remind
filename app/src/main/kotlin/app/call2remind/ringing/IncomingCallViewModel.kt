package app.call2remind.ringing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** UI state of the incoming / answered call screen. */
data class CallUiState(
    val loading: Boolean = true,
    val occurrenceId: String? = null,
    val title: String = "",
    val notes: String? = null,
    val sourceType: SourceType? = null,
    val fireAt: Instant? = null,
    val answered: Boolean = false,
    /** The occurrence is no longer ringing (or is gone): close the screen. */
    val finished: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val occurrences: OccurrenceRepository,
    private val reminders: ReminderRepository,
    private val settings: SettingsRepository,
    private val engine: SchedulingEngine,
    tts: TtsPlayer,
) : ViewModel() {

    private val occurrenceId = MutableStateFlow(savedStateHandle.get<String>(IncomingCallActivity.EXTRA_OCCURRENCE_ID))

    /** TTS progress for word highlighting. */
    val ttsEvents: SharedFlow<TtsEvent> = tts.events

    val state: StateFlow<CallUiState> = occurrenceId.filterNotNull()
        .flatMapLatest { id ->
            occurrences.observe(id).flatMapLatest { occurrence ->
                if (occurrence == null) {
                    flowOf(CallUiState(loading = false, occurrenceId = id, finished = true))
                } else {
                    reminders.observe(occurrence.reminderId).map { reminder ->
                        CallUiState(
                            loading = false,
                            occurrenceId = id,
                            title = reminder?.title.orEmpty(),
                            notes = reminder?.notes,
                            sourceType = occurrence.sourceType,
                            fireAt = occurrence.fireAt,
                            answered = occurrence.answeredAt != null,
                            finished = occurrence.state != OccurrenceState.RINGING,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CallUiState())

    /** Shows [id] instead of the current occurrence (new intent). */
    fun bind(id: String) {
        occurrenceId.value = id
    }

    fun answer() = send(OccurrenceEvent.Answer)

    fun decline() = send(OccurrenceEvent.Decline)

    fun done() = send(OccurrenceEvent.Done)

    /** Snooze for [duration], or the configured snooze length if `null`. */
    fun snooze(duration: Duration? = null) {
        val id = occurrenceId.value ?: return
        viewModelScope.launch {
            val length = duration ?: settings.current().snoozePolicy.defaultSnooze
            engine.handle(id, OccurrenceEvent.Snooze(length))
        }
    }

    private fun send(event: OccurrenceEvent) {
        val id = occurrenceId.value ?: return
        viewModelScope.launch { engine.handle(id, event) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
