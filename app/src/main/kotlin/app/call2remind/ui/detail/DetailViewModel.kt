package app.call2remind.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.data.repo.SourceRepository
import app.call2remind.di.ApplicationScope
import app.call2remind.di.IoDispatcher
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.Settings
import app.call2remind.settings.SettingsRepository
import app.call2remind.sources.SourceIds
import app.call2remind.sources.habit.HabitRepository
import app.call2remind.ui.ringtone.RingtoneCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Where the ringtone of a reminder comes from (Detail explains it). */
enum class RingtoneOrigin { REMINDER, SOURCE, APP_DEFAULT }

data class DetailUiState(
    val loading: Boolean = true,
    val reminder: Reminder? = null,
    /** The occurrence Detail was opened for, or the reminder's next one if that is gone. */
    val occurrence: Occurrence? = null,
    val lastSyncAt: Instant? = null,
    val settings: Settings = Settings(),
    val ringtoneTitle: String? = null,
    val ringtoneOrigin: RingtoneOrigin = RingtoneOrigin.APP_DEFAULT,
) {
    val isHabit: Boolean get() = reminder != null && reminder.sourceType == SourceType.HABIT && reminder.schedule is Schedule.Recurring

    /** Skip is possible while the occurrence still waits to ring. */
    val canSkip: Boolean get() = occurrence?.state == OccurrenceState.SCHEDULED || occurrence?.state == OccurrenceState.SNOOZED
}

sealed interface DetailEvent {
    /** The reminder is gone / was skipped or deleted: leave Detail. */
    data object Close : DetailEvent
}

/**
 * Reminder detail. Observes the reminder and the occurrence it was opened from (falling back to
 * the reminder's next active one, e.g. after it was paused and resumed), the source's last sync
 * and the settings that decide decline / ringtone behaviour.
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    reminders: ReminderRepository,
    private val occurrences: OccurrenceRepository,
    sources: SourceRepository,
    settingsRepository: SettingsRepository,
    private val engine: SchedulingEngine,
    private val habits: HabitRepository,
    private val catalog: RingtoneCatalog,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val occurrenceId: String = checkNotNull(savedStateHandle[ARG_OCCURRENCE])
    private val reminderId: String = checkNotNull(savedStateHandle[ARG_REMINDER])

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DetailEvent> = events.receiveAsFlow()

    private val reminder = reminders.observe(reminderId)

    private val occurrence: Flow<Occurrence?> = combine(
        occurrences.observe(occurrenceId),
        occurrences.observeUpcoming().map { list -> list.firstOrNull { it.occurrence.reminderId == reminderId }?.occurrence },
    ) { opened, next -> opened?.takeIf { it.state.isActive } ?: next ?: opened }

    private val ringtone: Flow<Pair<String?, RingtoneOrigin>> = combine(reminder, settingsRepository.settings) { r, s ->
        val own = r?.ringtoneUri
        val bySource = r?.let { s.sourceRingtones[it.sourceType] }
        when {
            own != null -> own to RingtoneOrigin.REMINDER
            bySource != null -> bySource to RingtoneOrigin.SOURCE
            else -> s.defaultRingtoneUri to RingtoneOrigin.APP_DEFAULT
        }
    }.distinctUntilChanged().map { (uri, origin) -> catalog.titleFor(uri) to origin }.flowOn(io)

    private val lastSync = combine(reminder, sources.observeAll()) { r, rows ->
        r?.let { rem -> rows.firstOrNull { it.id == SourceIds.forType(rem.sourceType) }?.lastSyncAt }
    }

    private val closing = MutableStateFlow(false)

    val state: StateFlow<DetailUiState> = combine(
        reminder,
        occurrence,
        lastSync,
        settingsRepository.settings,
        ringtone,
    ) { r, o, sync, s, tone ->
        DetailUiState(
            loading = false,
            reminder = r,
            occurrence = o,
            lastSyncAt = sync,
            settings = s,
            ringtoneTitle = tone.first,
            ringtoneOrigin = tone.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            // The reminder was deleted (source removed it, habit deleted elsewhere): nothing to show.
            reminder.collect { if (it == null && !closing.value) close() }
        }
    }

    /** Skips the pending occurrence ("Skip today"), then leaves. */
    fun skip() {
        val id = state.value.occurrence?.id ?: return
        appScope.launch { engine.handle(id, OccurrenceEvent.Skip) }
        close()
    }

    /** "Ring for this event": pauses / resumes the reminder (its pending calls are cancelled / replanned). */
    fun setRinging(enabled: Boolean) {
        val r = state.value.reminder ?: return
        if (r.enabled == enabled) return
        appScope.launch {
            if (state.value.isHabit) {
                habits.setEnabled(r.externalId, enabled)
            } else {
                engine.upsertReminders(listOf(r.copy(enabled = enabled)), SourceIds.forType(r.sourceType))
            }
        }
    }

    fun deleteHabit() {
        val r = state.value.reminder ?: return
        if (!state.value.isHabit) return
        closing.value = true
        appScope.launch { habits.delete(r.externalId) }
        close()
    }

    private fun close() {
        closing.value = true
        events.trySend(DetailEvent.Close)
    }

    companion object {
        const val ARG_OCCURRENCE = "occurrenceId"
        const val ARG_REMINDER = "reminderId"
    }
}
