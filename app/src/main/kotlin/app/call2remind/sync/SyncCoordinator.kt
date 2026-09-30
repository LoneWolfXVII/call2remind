package app.call2remind.sync

import android.util.Log
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.sync.Backoff
import app.call2remind.data.model.ReminderSource as SourceRow
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.data.repo.SourceRepository
import app.call2remind.scheduling.ReplanReason
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.ReminderSource
import app.call2remind.sources.RetryableSyncException
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceIds
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.sources.samsung.NotificationAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the pull sources and feeds their results into Room + the scheduling engine.
 *
 * Per source, in one serialized run:
 * 1. Turned off in settings → its row and reminders are deleted ([SyncState.Disabled]).
 * 2. Still backing off from an earlier failure and not forced → skipped.
 * 3. [ReminderSource.availability]: missing permission → [SyncState.NeedsPermission], account not
 *    connected → [SyncState.NotConnected]; stored reminders are kept, nothing crashes.
 * 4. [ReminderSource.snapshot] with the stored cursor. Full snapshots replace the source's
 *    reminders; deltas upsert + delete. Then `markSynced(id, now, cursor)`.
 * 5. Failures are isolated: one source failing never blocks the others. Each failure schedules the
 *    source's next non-forced attempt with [backoff]; retryable ones (network, 429/5xx) make
 *    [SyncRunResult.needsRetry] true so the worker returns `Result.retry()`.
 *
 * After all sources, one [SchedulingEngine.replan] if anything changed. Samsung Reminders is
 * reactive (notification listener) and has no pull source; its status is derived from the setting
 * and notification access, and its old one-shot reminders are pruned here. Habits are local
 * reminders managed by [app.call2remind.sources.habit.HabitRepository] and are never synced.
 */
@Singleton
class SyncCoordinator(
    sources: Set<ReminderSource>,
    private val sourceRepo: SourceRepository,
    private val reminderRepo: ReminderRepository,
    private val engine: SchedulingEngine,
    private val settings: SettingsRepository,
    private val notificationAccess: NotificationAccess,
    private val clock: Clock,
    private val backoff: Backoff,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        sources: Set<@JvmSuppressWildcards ReminderSource>,
        sourceRepo: SourceRepository,
        reminderRepo: ReminderRepository,
        engine: SchedulingEngine,
        settings: SettingsRepository,
        notificationAccess: NotificationAccess,
        clock: Clock,
    ) : this(
        sources, sourceRepo, reminderRepo, engine, settings, notificationAccess, clock,
        Backoff(base = DEFAULT_BACKOFF_BASE, cap = DEFAULT_BACKOFF_CAP), ZoneId::systemDefault,
    )

    private data class Failure(val count: Int, val nextAttemptAt: Instant)

    private val sourcesByType: Map<SourceType, ReminderSource> = sources.associateBy { it.type }
    private val mutex = Mutex()
    private val states = MutableStateFlow<Map<SourceType, SyncState>>(emptyMap())
    private val failures = HashMap<SourceType, Failure>()

    /** Source types shown on the Sources screen, in [SourceType] order. */
    val types: List<SourceType> = (sourcesByType.keys + SourceType.SAMSUNG_REMINDER).sortedBy { it.ordinal }

    /** Status of every [types] entry; `lastSyncAt` comes from the `sources` table. */
    val statuses: Flow<List<SyncStatus>> = combine(sourceRepo.observeAll(), states) { rows, current ->
        val lastSync = rows.associate { it.id to it.lastSyncAt }
        types.map { type ->
            val id = SourceIds.forType(type)
            SyncStatus(type, id, current[type] ?: SyncState.Idle, lastSync[id])
        }
    }.distinctUntilChanged()

    /** Status of one source type. */
    fun status(type: SourceType): Flow<SyncStatus> =
        statuses.mapNotNull { list -> list.firstOrNull { it.type == type } }.distinctUntilChanged()

    /** Current in-memory state of [type] (for tests / quick checks). */
    fun currentState(type: SourceType): SyncState = states.value[type] ?: SyncState.Idle

    /** Syncs every source in [scope]. Non-forced runs respect per-source backoff. */
    suspend fun sync(scope: SyncScope = SyncScope.ALL, force: Boolean = false): SyncRunResult {
        val types = sourcesByType.values.filter {
            when (scope) {
                SyncScope.ALL -> true
                SyncScope.LOCAL -> !it.requiresNetwork
                SyncScope.CLOUD -> it.requiresNetwork
            }
        }.map { it.type }
        return sync(types.toSet(), force, housekeeping = scope != SyncScope.CLOUD)
    }

    /** Syncs the given source types now (e.g. after connecting one, or a calendar change). */
    suspend fun sync(types: Set<SourceType>, force: Boolean = true): SyncRunResult =
        sync(types, force, housekeeping = false)

    private suspend fun sync(types: Set<SourceType>, force: Boolean, housekeeping: Boolean): SyncRunResult = mutex.withLock {
        val sourceSettings = settings.current().sources
        val outcomes = LinkedHashMap<SourceType, SourceOutcome>()
        var changed = false
        for (type in types.sortedBy { it.ordinal }) {
            val source = sourcesByType[type] ?: continue
            val (outcome, didChange) = runOne(source, sourceSettings, force)
            outcomes[type] = outcome
            changed = changed || didChange
        }
        updateSamsungState(sourceSettings)
        if (housekeeping) changed = pruneSamsungReminders() || changed
        val replanFailed = changed && !replanSafely()
        SyncRunResult(outcomes, replanFailed)
    }

    /**
     * Recomputes permission / connection / enable states without syncing (e.g. when the Sources
     * screen resumes after the user granted a permission). Error states are kept.
     */
    suspend fun refreshStatuses() = mutex.withLock {
        val sourceSettings = settings.current().sources
        for (source in sourcesByType.values) {
            val state = when {
                !sourceSettings.isEnabled(source.type) -> SyncState.Disabled
                else -> when (val availability = availabilitySafely(source)) {
                    is SourceAvailability.NeedsPermission -> SyncState.NeedsPermission(availability.permission)
                    SourceAvailability.NotConnected -> SyncState.NotConnected
                    SourceAvailability.Ready -> when (val current = currentState(source.type)) {
                        is SyncState.Error, SyncState.Syncing -> current
                        else -> SyncState.Idle
                    }
                }
            }
            setState(source.type, state)
        }
        updateSamsungState(sourceSettings)
    }

    /**
     * Turns a source on or off (Sources screen toggle). Off deletes its synced reminders and
     * replans; on syncs it right away (for Samsung only the setting and status change).
     */
    suspend fun setEnabled(type: SourceType, enabled: Boolean): SyncRunResult {
        settings.update { it.copy(sources = it.sources.withEnabled(type, enabled)) }
        if (enabled) return sync(setOf(type), force = true)
        return mutex.withLock {
            val source = sourcesByType[type]
            val removed = source != null && removeSourceData(source)
            if (source != null) {
                failures.remove(type)
                setState(type, SyncState.Disabled)
            }
            updateSamsungState(settings.current().sources)
            SyncRunResult(if (source != null) mapOf(type to SourceOutcome.Skipped(SyncState.Disabled)) else emptyMap(), removed && !replanSafely())
        }
    }

    /** Disconnects a cloud account: forgets the token, deletes its reminders and replans. */
    suspend fun disconnect(type: SourceType) = mutex.withLock {
        val source = sourcesByType[type] ?: return@withLock
        source.disconnect()
        failures.remove(type)
        if (removeSourceData(source)) replanSafely()
        setState(type, SyncState.NotConnected)
    }

    private suspend fun runOne(source: ReminderSource, sourceSettings: SourceSettings, force: Boolean): Pair<SourceOutcome, Boolean> {
        val type = source.type
        if (!sourceSettings.isEnabled(type)) {
            failures.remove(type)
            setState(type, SyncState.Disabled)
            return SourceOutcome.Skipped(SyncState.Disabled) to removeSourceData(source)
        }
        val now = clock.instant()
        val failure = failures[type]
        if (!force && failure != null && now.isBefore(failure.nextAttemptAt)) {
            return SourceOutcome.Skipped(currentState(type)) to false
        }
        when (val availability = availabilitySafely(source)) {
            is SourceAvailability.NeedsPermission -> return skip(type, SyncState.NeedsPermission(availability.permission))
            SourceAvailability.NotConnected -> return skip(type, SyncState.NotConnected)
            SourceAvailability.Ready -> Unit
        }
        setState(type, SyncState.Syncing)
        return try {
            val row = ensureRow(source)
            val stored = reminderRepo.getAll().filter { it.sourceType == type }
            val request = SyncRequest(
                now = now,
                zone = zone(),
                cursor = row.syncCursor,
                settings = sourceSettings,
                knownExternalIds = stored.mapTo(HashSet()) { it.externalId },
            )
            val snapshot = source.snapshot(request)
            val changed = apply(source, snapshot, stored)
            sourceRepo.markSynced(source.sourceId, clock.instant(), snapshot.cursor)
            failures.remove(type)
            setState(type, SyncState.Idle)
            SourceOutcome.Synced to changed
        } catch (e: CancellationException) {
            setState(type, SyncState.Idle)
            throw e
        } catch (e: NeedsPermissionException) {
            skip(type, SyncState.NeedsPermission(e.permission))
        } catch (e: NotConnectedException) {
            skip(type, SyncState.NotConnected)
        } catch (e: Exception) {
            val retryable = e is RetryableSyncException || e is IOException
            val error = SyncState.Error(e.message ?: e.javaClass.simpleName, retryable)
            Log.w(TAG, "Sync of $type failed (retryable=$retryable)", e)
            recordFailure(type, now)
            setState(type, error)
            SourceOutcome.Failed(error) to false
        }
    }

    private fun skip(type: SourceType, state: SyncState): Pair<SourceOutcome, Boolean> {
        failures.remove(type)
        setState(type, state)
        return SourceOutcome.Skipped(state) to false
    }

    /** Stores [snapshot]; returns true if anything may have changed. */
    private suspend fun apply(source: ReminderSource, snapshot: SourceSnapshot, stored: List<Reminder>): Boolean = when (snapshot) {
        is SourceSnapshot.Full -> {
            reminderRepo.replaceForSource(source.sourceId, snapshot.reminders)
            true
        }
        is SourceSnapshot.Delta -> {
            if (snapshot.upserts.isNotEmpty()) reminderRepo.upsertAll(snapshot.upserts, source.sourceId)
            val idsByExternal = stored.associate { it.externalId to it.id }
            val ids = snapshot.removedExternalIds.mapNotNull { idsByExternal[it] }
            if (ids.isNotEmpty()) reminderRepo.delete(ids)
            snapshot.upserts.isNotEmpty() || ids.isNotEmpty()
        }
    }

    private suspend fun availabilitySafely(source: ReminderSource): SourceAvailability = try {
        source.availability()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Availability check of ${source.type} failed", e)
        SourceAvailability.Ready
    }

    private suspend fun ensureRow(source: ReminderSource): SourceRow =
        sourceRepo.get(source.sourceId) ?: SourceRow(id = source.sourceId, type = source.type).also { sourceRepo.upsert(it) }

    /** Deletes the source row and its reminders; true if there was a row. */
    private suspend fun removeSourceData(source: ReminderSource): Boolean {
        if (sourceRepo.get(source.sourceId) == null) return false
        sourceRepo.delete(source.sourceId)
        return true
    }

    private fun recordFailure(type: SourceType, now: Instant) {
        val count = (failures[type]?.count ?: 0) + 1
        failures[type] = Failure(count, now.plus(backoff.delayFor(count - 1)))
    }

    private fun updateSamsungState(sourceSettings: SourceSettings) {
        val state = when {
            !sourceSettings.isEnabled(SourceType.SAMSUNG_REMINDER) -> SyncState.Disabled
            !notificationAccess.isGranted() -> SyncState.NeedsPermission(NOTIFICATION_LISTENER_PERMISSION)
            else -> SyncState.Idle
        }
        setState(SourceType.SAMSUNG_REMINDER, state)
    }

    /** Samsung reminders are one-shot; drop them once they are older than the history retention. */
    private suspend fun pruneSamsungReminders(): Boolean {
        val cutoff = clock.instant().minus(SAMSUNG_RETENTION)
        val stale = reminderRepo.getAll()
            .filter { it.sourceType == SourceType.SAMSUNG_REMINDER }
            .filter { (it.schedule as? Schedule.At)?.instant?.isBefore(cutoff) == true }
            .map { it.id }
        if (stale.isEmpty()) return false
        reminderRepo.delete(stale)
        return true
    }

    private suspend fun replanSafely(): Boolean = try {
        engine.replan(ReplanReason.SYNC)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Replan after sync failed", e)
        false
    }

    private fun setState(type: SourceType, state: SyncState) {
        states.update { it + (type to state) }
    }

    companion object {
        private const val TAG = "SyncCoordinator"
        const val NOTIFICATION_LISTENER_PERMISSION: String = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        val DEFAULT_BACKOFF_BASE: Duration = Duration.ofMinutes(1)
        val DEFAULT_BACKOFF_CAP: Duration = Duration.ofHours(1)
        val SAMSUNG_RETENTION: Duration = SchedulingEngine.HISTORY_RETENTION
    }
}
