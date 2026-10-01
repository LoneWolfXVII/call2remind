package app.call2remind.ui.sources

import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.SourceType
import app.call2remind.di.ApplicationScope
import app.call2remind.di.IoDispatcher
import app.call2remind.settings.SettingsRepository
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.calendar.CalendarInfo
import app.call2remind.sources.calendar.CalendarReader
import app.call2remind.sources.habit.Habit
import app.call2remind.sources.habit.HabitRepository
import app.call2remind.sources.samsung.NotificationAccess
import app.call2remind.sync.SyncCoordinator
import app.call2remind.sync.SyncScope
import app.call2remind.sync.SyncState
import app.call2remind.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** A calendar in the picker. */
data class CalendarChoice(val info: CalendarInfo, val included: Boolean)

/** The summary line under the title ("Everything synced 3 min ago", "2 sources need attention"). */
sealed interface SourcesSummary {
    data object Syncing : SourcesSummary

    data class Synced(val at: Instant?) : SourcesSummary

    data class NeedsAttention(val count: Int) : SourcesSummary
}

data class SourcesUiState(
    val loading: Boolean = true,
    val statuses: List<SyncStatus> = emptyList(),
    val settings: SourceSettings = SourceSettings(),
    val habits: List<Habit> = emptyList(),
    val notificationAccess: Boolean = false,
    /** `null` until the picker loads them (or when calendar access is missing). */
    val calendars: List<CalendarChoice>? = null,
    val syncingAll: Boolean = false,
) {
    fun status(type: SourceType): SyncStatus? = statuses.firstOrNull { it.type == type }

    val summary: SourcesSummary
        get() {
            val active = statuses.filter { it.state != SyncState.Disabled }
            val attention = active.count { it.state is SyncState.Error || it.state is SyncState.NeedsPermission }
            return when {
                syncingAll || active.any { it.state == SyncState.Syncing } -> SourcesSummary.Syncing
                attention > 0 -> SourcesSummary.NeedsAttention(attention)
                else -> SourcesSummary.Synced(active.mapNotNull { it.lastSyncAt }.maxOrNull())
            }
        }
}

/**
 * Sources: per-source status and switches (through [SyncCoordinator]), the calendar picker
 * (excluded calendar ids in settings), birthdays' day-before call, habits and notification access
 * for Samsung Reminders. Statuses are recomputed on every resume ([refresh]) since permissions are
 * granted outside the app.
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val sync: SyncCoordinator,
    private val settings: SettingsRepository,
    private val habits: HabitRepository,
    private val calendarReader: CalendarReader,
    private val notificationAccess: NotificationAccess,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    private val clock: Clock,
) : ViewModel() {

    private val access = MutableStateFlow(notificationAccess.isGranted())
    private val calendars = MutableStateFlow<List<CalendarInfo>?>(null)
    private val syncingAll = MutableStateFlow(false)
    private var calendarsDirty = false

    val state: StateFlow<SourcesUiState> = combine(
        combine(sync.statuses, settings.settings.map { it.sources }) { s, src -> s to src },
        habits.observeAll().onStart { emit(emptyList()) },
        access,
        calendars,
        syncingAll,
    ) { (statuses, sources), habitList, granted, cals, all ->
        SourcesUiState(
            loading = false,
            statuses = statuses,
            settings = sources,
            habits = habitList.sortedBy { it.title.lowercase() },
            notificationAccess = granted,
            calendars = cals?.map { CalendarChoice(it, it.id !in sources.excludedCalendarIds) },
            syncingAll = all,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    /** On resume: permissions / notification access may have changed in system settings. */
    fun refresh() {
        access.value = notificationAccess.isGranted()
        viewModelScope.launch { sync.refreshStatuses() }
    }

    fun setEnabled(type: SourceType, enabled: Boolean) {
        appScope.launch { sync.setEnabled(type, enabled) }
    }

    fun syncAll() {
        if (syncingAll.value) return
        syncingAll.value = true
        appScope.launch {
            try {
                sync.sync(SyncScope.ALL, force = true)
            } finally {
                syncingAll.value = false
            }
        }
    }

    /** Syncs one source now (after a permission was granted). */
    fun syncOne(type: SourceType) {
        appScope.launch { sync.sync(setOf(type)) }
    }

    fun loadCalendars() {
        viewModelScope.launch {
            calendars.value = withContext(io) {
                if (calendarReader.hasPermission()) calendarReader.calendars().sortedWith(compareBy({ it.accountName.orEmpty() }, { it.displayName.orEmpty() })) else emptyList()
            }
        }
    }

    fun setCalendarIncluded(id: Long, included: Boolean) {
        calendarsDirty = true
        viewModelScope.launch {
            settings.update { s ->
                val excluded = if (included) s.sources.excludedCalendarIds - id else s.sources.excludedCalendarIds + id
                s.copy(sources = s.sources.copy(excludedCalendarIds = excluded))
            }
        }
    }

    /** Picker closed: re-read the calendar with the new selection if it changed. */
    fun onCalendarsClosed() {
        if (!calendarsDirty) return
        calendarsDirty = false
        appScope.launch { sync.sync(setOf(SourceType.CALENDAR)) }
    }

    fun setBirthdayDayBefore(on: Boolean) {
        appScope.launch {
            settings.update { it.copy(sources = it.sources.copy(birthdayDayBefore = on)) }
            sync.sync(setOf(SourceType.BIRTHDAY))
        }
    }

    fun setHabitEnabled(id: String, enabled: Boolean) {
        appScope.launch { habits.setEnabled(id, enabled) }
    }

    /** For "x min ago" labels. */
    fun now(): Instant = clock.instant()

    /** The Samsung listener, to highlight in the notification access settings. */
    val listenerComponent: ComponentName get() = notificationAccess.listenerComponent
}
