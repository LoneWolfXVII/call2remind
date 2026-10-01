package app.call2remind.ui.habit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.di.ApplicationScope
import app.call2remind.di.IoDispatcher
import app.call2remind.sources.habit.Habit
import app.call2remind.sources.habit.HabitRepository
import app.call2remind.ui.ringtone.RingtoneCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** The habit form's fields. */
data class HabitForm(
    val title: String = "",
    val days: Set<DayOfWeek> = emptySet(),
    val times: List<LocalTime> = emptyList(),
    val intervalWeeks: Int = 1,
    val ringtoneUri: String? = null,
    val ttsEnabled: Boolean = true,
    val notes: String = "",
)

/** What stops a form from saving. */
enum class HabitError { TITLE, DAYS, TIMES }

/** Pure validation (unit tested). */
object HabitValidation {
    const val MAX_TITLE = 120
    const val MAX_TIMES = 8
    val INTERVALS = 1..4

    fun errors(form: HabitForm): Set<HabitError> = buildSet {
        if (form.title.isBlank()) add(HabitError.TITLE)
        if (form.days.isEmpty()) add(HabitError.DAYS)
        if (form.times.isEmpty()) add(HabitError.TIMES)
    }
}

/** "Start from" presets on a new habit. */
enum class HabitTemplate(val days: Set<DayOfWeek>, val times: List<LocalTime>) {
    MEDICINE(DayOfWeek.values().toSet(), listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))),
    GYM(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY), listOf(LocalTime.of(18, 30))),
    WATER(DayOfWeek.values().toSet(), listOf(LocalTime.of(11, 0), LocalTime.of(15, 0), LocalTime.of(18, 0))),
    STANDUP(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), listOf(LocalTime.of(9, 25))),
}

data class HabitEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val form: HabitForm = HabitForm(),
    /** Errors are shown only after the first save attempt. */
    val showErrors: Boolean = false,
    val template: HabitTemplate? = null,
    val ringtoneTitle: String? = null,
    val saving: Boolean = false,
) {
    val errors: Set<HabitError> get() = HabitValidation.errors(form)
    val visibleErrors: Set<HabitError> get() = if (showErrors) errors else emptySet()
}

sealed interface HabitEditorEvent {
    data object Saved : HabitEditorEvent

    data object Deleted : HabitEditorEvent
}

/**
 * New habit / edit habit. Edits keep the habit's id, start date (so "every 2 weeks" keeps its
 * rhythm) and end; saving goes through [HabitRepository], which replans alarms immediately.
 */
@HiltViewModel
class HabitEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val habits: HabitRepository,
    private val catalog: RingtoneCatalog,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val habitId: String? = savedStateHandle[ARG_HABIT_ID]
    private var original: Habit? = null

    private val _state = MutableStateFlow(HabitEditorUiState(loading = habitId != null, isNew = habitId == null, form = newForm()))
    val state: StateFlow<HabitEditorUiState> = _state.asStateFlow()

    private val events = Channel<HabitEditorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HabitEditorEvent> = events.receiveAsFlow()

    init {
        if (habitId != null) {
            viewModelScope.launch {
                val habit = habits.get(habitId)
                original = habit
                if (habit == null) {
                    _state.update { it.copy(loading = false, isNew = true) }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            form = HabitForm(
                                title = habit.title,
                                days = habit.rule.daysOfWeek,
                                times = habit.rule.times.sorted(),
                                intervalWeeks = habit.rule.intervalWeeks,
                                ringtoneUri = habit.ringtoneUri,
                                ttsEnabled = habit.ttsEnabled,
                                notes = habit.notes.orEmpty(),
                            ),
                        )
                    }
                    loadRingtoneTitle(habit.ringtoneUri)
                }
            }
        }
    }

    private fun newForm(): HabitForm {
        // A sensible start: every day, at the next whole hour.
        val next = LocalTime.now(clock).plusHours(1).withMinute(0).withSecond(0).withNano(0)
        return HabitForm(days = DayOfWeek.values().toSet(), times = listOf(next))
    }

    private fun edit(transform: (HabitForm) -> HabitForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun setTitle(title: String) = edit { it.copy(title = title.take(HabitValidation.MAX_TITLE)) }

    fun toggleDay(day: DayOfWeek) = edit { if (day in it.days) it.copy(days = it.days - day) else it.copy(days = it.days + day) }

    /** Adds [time] (ignored if already there or at the limit); times stay sorted. */
    fun addTime(time: LocalTime) = edit {
        if (time in it.times || it.times.size >= HabitValidation.MAX_TIMES) it else it.copy(times = (it.times + time).sorted())
    }

    fun replaceTime(old: LocalTime, new: LocalTime) = edit {
        it.copy(times = (it.times - old + new).distinct().sorted())
    }

    fun removeTime(time: LocalTime) = edit { it.copy(times = it.times - time) }

    fun setInterval(weeks: Int) = edit { it.copy(intervalWeeks = weeks.coerceIn(HabitValidation.INTERVALS)) }

    fun setTts(enabled: Boolean) = edit { it.copy(ttsEnabled = enabled) }

    fun setNotes(notes: String) = edit { it.copy(notes = notes.take(MAX_NOTES)) }

    /** The ringtone picker returned; `null` = the app default. */
    fun setRingtone(uri: String?) {
        edit { it.copy(ringtoneUri = uri) }
        loadRingtoneTitle(uri)
    }

    fun applyTemplate(template: HabitTemplate, title: String) {
        _state.update {
            it.copy(
                template = template,
                form = it.form.copy(title = title, days = template.days, times = template.times.sorted()),
            )
        }
    }

    /** Validates; on success stores the habit (replanning its alarms) and emits [HabitEditorEvent.Saved]. */
    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(saving = true, showErrors = true) }
        val form = current.form
        val existing = original
        appScope.launch {
            if (existing == null) {
                habits.create(
                    title = form.title.trim(),
                    rule = RecurrenceRule(
                        daysOfWeek = form.days,
                        times = form.times.toSet(),
                        startDate = LocalDate.now(clock),
                        intervalWeeks = form.intervalWeeks,
                    ),
                    notes = form.notes.trim().ifEmpty { null },
                    ringtoneUri = form.ringtoneUri,
                    ttsEnabled = form.ttsEnabled,
                )
            } else {
                habits.update(
                    existing.copy(
                        title = form.title.trim(),
                        rule = existing.rule.copy(
                            daysOfWeek = form.days,
                            times = form.times.toSet(),
                            intervalWeeks = form.intervalWeeks,
                        ),
                        notes = form.notes.trim().ifEmpty { null },
                        ringtoneUri = form.ringtoneUri,
                        ttsEnabled = form.ttsEnabled,
                    ),
                )
            }
            events.send(HabitEditorEvent.Saved)
        }
    }

    fun delete() {
        val existing = original ?: return
        appScope.launch {
            habits.delete(existing.id)
            events.send(HabitEditorEvent.Deleted)
        }
    }

    private fun loadRingtoneTitle(uri: String?) {
        viewModelScope.launch {
            val title = withContext(io) { catalog.titleFor(uri) }
            _state.update { it.copy(ringtoneTitle = title) }
        }
    }

    companion object {
        const val ARG_HABIT_ID = "habitId"
        const val MAX_NOTES = 300
    }
}
