package app.call2remind.sources

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import app.call2remind.settings.SourceSettings
import java.time.Instant
import java.time.ZoneId

/**
 * A pull-based reminder source (calendar, birthdays, Google Tasks, Microsoft To Do).
 *
 * Implementations only read and normalize: [SyncCoordinator][app.call2remind.sync.SyncCoordinator]
 * stores the result, marks the source synced and replans. Every reminder a source returns must use
 * `id = ReminderIds.of(type, externalId)`.
 *
 * Failures are reported by throwing a [SourceException] (or an [java.io.IOException], which the
 * coordinator treats as retryable).
 */
interface ReminderSource {
    val type: SourceType

    /** Stable id of this source's row in `sources` (one row per type in v1). */
    val sourceId: String get() = SourceIds.forType(type)

    /** True for cloud sources: they run in the network-constrained worker. */
    val requiresNetwork: Boolean

    /** Whether the source can be read right now (permission granted / account connected). */
    suspend fun availability(): SourceAvailability

    /**
     * Reads the source. With `request.cursor == null` the result must be a
     * [SourceSnapshot.Full]; otherwise the source may return a [SourceSnapshot.Delta].
     */
    suspend fun snapshot(request: SyncRequest): SourceSnapshot

    /** Forgets the connected account (cloud sources); no-op for local sources. */
    suspend fun disconnect() = Unit
}

/** Input for [ReminderSource.snapshot]. */
data class SyncRequest(
    val now: Instant,
    /** Device zone used for wall-clock (date-only / annual) reminders. */
    val zone: ZoneId,
    /** Cursor returned by the previous successful sync, `null` for a full sync. */
    val cursor: String?,
    val settings: SourceSettings,
    /** External ids of the reminders currently stored for this source (for delta bookkeeping). */
    val knownExternalIds: Set<String> = emptySet(),
)

/** What a sync read. */
sealed interface SourceSnapshot {
    /** Cursor to pass back next time (`null` = next sync is full). */
    val cursor: String?

    /** The complete set of this source's reminders; everything else of the source is deleted. */
    data class Full(val reminders: List<Reminder>, override val cursor: String? = null) : SourceSnapshot

    /** Changes since the cursor: [upserts] are stored, [removedExternalIds] deleted. */
    data class Delta(
        val upserts: List<Reminder>,
        val removedExternalIds: Set<String>,
        override val cursor: String?,
    ) : SourceSnapshot
}

/** Whether a source can be read. */
sealed interface SourceAvailability {
    data object Ready : SourceAvailability

    /** A runtime permission (or special access) is missing, e.g. `READ_CALENDAR`. */
    data class NeedsPermission(val permission: String) : SourceAvailability

    /** A cloud account is not connected (or its authorization expired). */
    data object NotConnected : SourceAvailability
}

/** Typed sync failures; anything else is treated as a generic error. */
sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The permission was revoked between the availability check and the read. */
class NeedsPermissionException(val permission: String) : SourceException("Missing permission $permission")

/** No access token, or the server rejected a freshly fetched one. */
class NotConnectedException(message: String = "Account not connected") : SourceException(message)

/** Temporary failure (network, 429, 5xx): retry later with backoff. */
class RetryableSyncException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** Permanent failure (4xx other than 401/429, malformed response…): report, don't hammer. */
open class PermanentSyncException(message: String, cause: Throwable? = null) : SourceException(message, cause)

/** Fixed `sources` row ids, one per source type (v1 has a single account per type). */
object SourceIds {
    const val CALENDAR = "calendar"
    const val BIRTHDAYS = "birthdays"
    const val HABITS = "habits"
    const val GOOGLE_TASKS = "google_tasks"
    const val MS_TODO = "ms_todo"
    const val SAMSUNG_REMINDERS = "samsung_reminders"

    fun forType(type: SourceType): String = when (type) {
        SourceType.CALENDAR -> CALENDAR
        SourceType.BIRTHDAY -> BIRTHDAYS
        SourceType.HABIT -> HABITS
        SourceType.GOOGLE_TASKS -> GOOGLE_TASKS
        SourceType.MS_TODO -> MS_TODO
        SourceType.SAMSUNG_REMINDER -> SAMSUNG_REMINDERS
    }
}
