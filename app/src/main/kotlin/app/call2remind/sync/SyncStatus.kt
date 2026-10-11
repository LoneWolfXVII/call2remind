package app.call2remind.sync

import app.call2remind.core.model.SourceType
import java.time.Instant

/** What a source is doing / why it can't sync, for the Sources screen. */
sealed interface SyncState {
    data object Idle : SyncState

    data object Syncing : SyncState

    /** Last sync failed. [retryable] errors are retried automatically with backoff. */
    data class Error(val message: String, val retryable: Boolean) : SyncState

    /** A permission / special access is missing (e.g. `android.permission.READ_CALENDAR`). */
    data class NeedsPermission(val permission: String) : SyncState

    /** Cloud account not connected (or authorization expired): show "Connect". */
    data object NotConnected : SyncState

    /** Turned off in settings. */
    data object Disabled : SyncState
}

/** Status of one source type. */
data class SyncStatus(
    val type: SourceType,
    val sourceId: String,
    val state: SyncState,
    val lastSyncAt: Instant?,
)

/** Which sources a sync run covers. */
enum class SyncScope {
    /** Device sources (calendar, contacts): no network needed. */
    LOCAL,

    /** Cloud sources (Google Tasks, Microsoft To Do): need a network. */
    CLOUD,

    ALL,
}

/** Outcome of one source in a run. */
sealed interface SourceOutcome {
    data object Synced : SourceOutcome

    /** Skipped: disabled, missing permission, not connected, or still backing off. */
    data class Skipped(val state: SyncState) : SourceOutcome

    data class Failed(val error: SyncState.Error) : SourceOutcome
}

/** Result of [SyncCoordinator.sync]. */
data class SyncRunResult(val outcomes: Map<SourceType, SourceOutcome>, val replanFailed: Boolean = false) {
    /** True if a retryable failure happened: the worker should return `Result.retry()`. */
    val needsRetry: Boolean
        get() = replanFailed || outcomes.values.any { it is SourceOutcome.Failed && it.error.retryable }
}
