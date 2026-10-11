package app.call2remind.ui.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.model.OccurrenceKey
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.di.ApplicationScope
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.sources.SourceIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class HistoryFilter { ALL, MISSED }

/** One finished call. */
@Immutable
data class HistoryItem(
    val occurrenceId: String,
    val title: String,
    val source: SourceType,
    val plannedAt: Instant,
    val fireAt: Instant,
    val state: OccurrenceState,
    /** Rings after the first (declines, time-outs, snoozes). */
    val ringBacks: Int,
)

@Immutable
data class HistoryDay(val date: LocalDate, val items: List<HistoryItem>)

@Immutable
data class HistoryModel(val missed: List<HistoryItem> = emptyList(), val days: List<HistoryDay> = emptyList()) {
    val isEmpty: Boolean get() = missed.isEmpty() && days.isEmpty()
}

/** A ring-log line for the debug view, with the reminder's title when it is still known. */
@Immutable
data class LogLine(val event: RingLogEvent, val title: String?, val source: SourceType?)

/** Pure grouping (unit tested). */
object HistoryGrouping {
    /**
     * Missed calls (newest first) go to [HistoryModel.missed]; with [HistoryFilter.ALL] the done
     * and skipped ones follow, grouped by local day of their ring, newest day first. Missed rows
     * already being rung again ([ringing]) are left out.
     */
    fun build(
        history: List<OccurrenceWithReminder>,
        filter: HistoryFilter,
        zone: ZoneId,
        ringing: Set<String> = emptySet(),
    ): HistoryModel {
        val items = history.filter { it.reminder != null && it.occurrence.state.isTerminal }.map { it.toItem() }
        val missed = items.filter { it.state == OccurrenceState.MISSED && it.occurrenceId !in ringing }.sortedByDescending { it.fireAt }
        if (filter == HistoryFilter.MISSED) return HistoryModel(missed = missed)
        val days = items
            .filter { it.state != OccurrenceState.MISSED }
            .groupBy { it.fireAt.atZone(zone).toLocalDate() }
            .map { (date, list) -> HistoryDay(date, list.sortedByDescending { it.fireAt }) }
            .sortedByDescending { it.date }
        return HistoryModel(missed, days)
    }

    private fun OccurrenceWithReminder.toItem() = HistoryItem(
        occurrenceId = occurrence.id,
        title = requireNotNull(reminder).title,
        source = occurrence.sourceType,
        plannedAt = occurrence.plannedAt,
        fireAt = occurrence.fireAt,
        state = occurrence.state,
        ringBacks = occurrence.ringBacks,
    )
}

data class HistoryUiState(
    val loading: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val model: HistoryModel = HistoryModel(),
    val showLog: Boolean = false,
    val log: List<LogLine> = emptyList(),
)

/**
 * Missed & history. Missed calls can be rung again right away or marked done; the ring log (every
 * FIRED / ANSWERED / SNOOZED / … / FAILED with its reason) is a debug view behind a long press on
 * the title.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val occurrences: OccurrenceRepository,
    private val reminders: ReminderRepository,
    private val engine: SchedulingEngine,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val showLog = MutableStateFlow(false)
    private val reRung = MutableStateFlow<Set<String>>(emptySet())

    private val history = occurrences.observeHistory(HISTORY_LIMIT)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val log = showLog.flatMapLatest { show ->
        if (!show) {
            flowOf(emptyList())
        } else {
            combine(occurrences.observeRingLog(LOG_LIMIT), history, occurrences.observeUpcoming()) { events, past, upcoming ->
                val known = (past + upcoming).associateBy { it.occurrence.id }
                events.map { e ->
                    val row = known[e.occurrenceId]
                    LogLine(e, row?.reminder?.title, row?.occurrence?.sourceType ?: OccurrenceKey.parse(e.occurrenceId)?.sourceType)
                }
            }
        }
    }

    val state: StateFlow<HistoryUiState> = combine(history, filter, showLog, log, reRung) { h, f, show, lines, rung ->
        HistoryUiState(
            loading = false,
            filter = f,
            model = HistoryGrouping.build(h, f, clock.zone, rung),
            showLog = show,
            log = lines,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    fun toggleLog() {
        showLog.update { !it }
    }

    fun markDone(occurrenceId: String) {
        appScope.launch { engine.handle(occurrenceId, OccurrenceEvent.Done) }
    }

    /**
     * "Ring me now": a fresh occurrence of the same reminder that rings immediately through the
     * normal alarm path (claim → ringing service → call screen). The missed row stays missed in the
     * data (it was missed); it leaves the Missed list for this session so it isn't offered twice.
     */
    fun ringAgain(occurrenceId: String) {
        reRung.update { it + occurrenceId }
        appScope.launch {
            val missed = occurrences.get(occurrenceId) ?: return@launch
            val reminder = reminders.get(missed.reminderId) ?: return@launch
            engine.ringImmediately(reminder, clock.instant(), SourceIds.forType(reminder.sourceType))
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 300
        const val LOG_LIMIT = 300
    }
}
