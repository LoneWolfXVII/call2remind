package app.call2remind.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.TransitionResult
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.di.ApplicationScope
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.sync.SyncCoordinator
import app.call2remind.sync.SyncScope
import app.call2remind.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** What a swipe on a timeline row does. */
enum class SwipeKind { DONE, SKIP }

/** A swiped row waiting out its undo window. */
data class PendingSwipe(val occurrenceId: String, val title: String, val kind: SwipeKind)

data class UpNextUiState(
    val loading: Boolean = true,
    val model: UpNextModel = UpNextModel(),
    /** A source is syncing, for any reason (spins the sync button). */
    val syncing: Boolean = false,
    /** A sync the user asked for (pull / button) is running: holds the pull indicator. */
    val refreshing: Boolean = false,
    val pending: PendingSwipe? = null,
)

/**
 * Up next: active occurrences (+ today's finished ones) as a [Timeline], swipe Done / Skip with
 * undo, and manual sync.
 *
 * Swipes are optimistic and deferred: the row hides at once, and the engine transition runs only
 * when the undo window ends ([commitPending], driven by the undo bar's timer), when another swipe
 * replaces it, or when the screen goes away. Undo just un-hides it — terminal states can't be
 * reverted, so nothing is written until the user has had their chance. Commits run in the
 * application scope so leaving the screen never loses one.
 */
@HiltViewModel
class UpNextViewModel @Inject constructor(
    private val occurrences: OccurrenceRepository,
    private val engine: SchedulingEngine,
    private val sync: SyncCoordinator,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val pending = MutableStateFlow<PendingSwipe?>(null)

    /** Rows hidden while pending, and after commit until Room reports them finished. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val manualSync = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)

    private val model = combine(
        occurrences.observeUpcoming(UPCOMING_LIMIT),
        occurrences.observeHistory(HISTORY_LIMIT),
        hidden,
        refresh,
    ) { upcoming, history, hiddenIds, _ ->
        // Forget committed ids once they have left the active list.
        val activeIds = upcoming.mapTo(HashSet()) { it.occurrence.id }
        val keep = hiddenIds.filterTo(HashSet()) { it in activeIds || it == pending.value?.occurrenceId }
        if (keep.size != hiddenIds.size) hidden.value = keep
        Timeline.build(upcoming, history, clock.instant(), clock.zone, hiddenIds)
    }

    private val anySyncing = sync.statuses.map { list -> list.any { it.state == SyncState.Syncing } }.onStart { emit(false) }

    val state: StateFlow<UpNextUiState> = combine(model, anySyncing, manualSync, pending) { m, any, manual, p ->
        UpNextUiState(loading = false, model = m, syncing = any || manual, refreshing = manual, pending = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UpNextUiState())

    /** The current instant (tests drive a fake clock). */
    fun now(): Instant = clock.instant()

    /** Re-groups the timeline (called on the minute tick so "Today" follows midnight). */
    fun tick() {
        refresh.update { it + 1 }
    }

    fun onSwipe(row: TimelineRow, kind: SwipeKind) {
        if (row.isRinging || row.isFinished) return
        commitPending()
        pending.value = PendingSwipe(row.occurrenceId, row.title, kind)
        hidden.update { it + row.occurrenceId }
    }

    fun undo() {
        val p = pending.value ?: return
        pending.value = null
        hidden.update { it - p.occurrenceId }
    }

    /** Applies the pending swipe (undo window over). */
    fun commitPending() {
        val p = pending.value ?: return
        pending.value = null
        appScope.launch {
            val event = when (p.kind) {
                SwipeKind.DONE -> OccurrenceEvent.Done
                SwipeKind.SKIP -> OccurrenceEvent.Skip
            }
            val result = engine.handle(p.occurrenceId, event)
            if (result !is TransitionResult.Transitioned) {
                // It changed meanwhile (started ringing, was deleted): show whatever it is now.
                hidden.update { it - p.occurrenceId }
            }
        }
    }

    /** Pull-to-refresh / sync button: syncs every source now. */
    fun syncNow() {
        if (manualSync.value) return
        manualSync.value = true
        appScope.launch {
            try {
                sync.sync(SyncScope.ALL, force = true)
            } finally {
                manualSync.value = false
            }
        }
    }

    override fun onCleared() {
        commitPending()
    }

    private companion object {
        const val UPCOMING_LIMIT = 200
        const val HISTORY_LIMIT = 60
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
