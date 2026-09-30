package app.call2remind.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.scheduling.SchedulingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Where the self-test is. */
enum class SelfTestPhase {
    /** Not started: offer "Ring me in 1 minute". */
    IDLE,

    /** Scheduling in progress. */
    STARTING,

    /** Armed; counting down to [SelfTestState.fireAt]. */
    SCHEDULED,

    /** The engine started ringing it (it may since be answered, snoozed or done). */
    RANG,

    /** Well past its time and still not rung: the device is holding the alarm back. */
    LATE,
}

data class SelfTestState(
    val phase: SelfTestPhase = SelfTestPhase.IDLE,
    val fireAt: Instant? = null,
    val remaining: Duration = Duration.ZERO,
)

/**
 * "Ring me in 1 minute": stores a one-off reminder through the real engine
 * ([SchedulingEngine.upsertReminders] with `Schedule.At(now + 1 min)`), so the test exercises the
 * same alarm → receiver → ringing service → full-screen call path as every real reminder.
 */
@HiltViewModel
class SelfTestViewModel @Inject constructor(
    private val engine: SchedulingEngine,
    private val occurrences: OccurrenceRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(SelfTestState())
    val state: StateFlow<SelfTestState> = _state.asStateFlow()

    private var watch: Job? = null

    fun start(title: String) {
        if (_state.value.phase == SelfTestPhase.STARTING || _state.value.phase == SelfTestPhase.SCHEDULED) return
        _state.value = SelfTestState(SelfTestPhase.STARTING)
        viewModelScope.launch {
            val fireAt = clock.instant().plus(LEAD).truncatedTo(ChronoUnit.SECONDS)
            val reminder = testReminder(title, fireAt)
            engine.upsertReminders(listOf(reminder))
            _state.value = SelfTestState(SelfTestPhase.SCHEDULED, fireAt, Duration.between(clock.instant(), fireAt))
            watch(Occurrence.scheduled(reminder, fireAt).id, fireAt)
        }
    }

    /** Cancels a pending test call (deletes the test reminder and its alarm). */
    fun cancel() {
        watch?.cancel()
        _state.value = SelfTestState()
        viewModelScope.launch { removeIfPending() }
    }

    /** Cleans up before leaving: a test call that never rang must not ring later. */
    fun onLeave() {
        watch?.cancel()
        viewModelScope.launch { removeIfPending() }
    }

    private suspend fun removeIfPending() {
        if (occurrences.getPending().any { it.reminderId == REMINDER_ID }) {
            engine.deleteReminders(listOf(REMINDER_ID))
        }
    }

    private fun watch(occurrenceId: String, fireAt: Instant) {
        watch?.cancel()
        watch = viewModelScope.launch {
            launch {
                occurrences.observe(occurrenceId).first { it != null && it.state != OccurrenceState.SCHEDULED }
                _state.update { it.copy(phase = SelfTestPhase.RANG, remaining = Duration.ZERO) }
            }
            while (isActive && _state.value.phase != SelfTestPhase.RANG) {
                val remaining = Duration.between(clock.instant(), fireAt)
                _state.update { current ->
                    when {
                        current.phase != SelfTestPhase.SCHEDULED && current.phase != SelfTestPhase.LATE -> current
                        remaining <= LATE_AFTER.negated() -> current.copy(phase = SelfTestPhase.LATE, remaining = Duration.ZERO)
                        else -> current.copy(remaining = if (remaining.isNegative) Duration.ZERO else remaining)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    companion object {
        val LEAD: Duration = Duration.ofMinutes(1)

        /** A test call this late (and not rung) means the OS is deferring alarms. */
        val LATE_AFTER: Duration = Duration.ofSeconds(45)
        private const val TICK_MS = 250L
        private const val EXTERNAL_ID = "self-test"
        val REMINDER_ID: String = ReminderIds.of(SourceType.HABIT, EXTERNAL_ID)

        fun testReminder(title: String, at: Instant): Reminder = Reminder(
            id = REMINDER_ID,
            sourceType = SourceType.HABIT,
            externalId = EXTERNAL_ID,
            title = title,
            schedule = Schedule.At(at),
            zone = ZoneId.systemDefault(),
        )
    }
}
