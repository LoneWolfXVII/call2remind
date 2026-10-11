package app.call2remind.sources.google

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.ReminderSource
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.sources.auth.TokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Google Tasks → date-only reminders.
 *
 * - **Full sync** (no cursor, unreadable cursor, or last full sync > [FULL_SYNC_INTERVAL] ago):
 *   every list, `showCompleted=false&showHidden=false`, `dueMin` = start of yesterday (UTC) →
 *   [SourceSnapshot.Full].
 * - **Incremental** (cursor): every list with `updatedMin` = cursor, completed/hidden/deleted
 *   included so they can be removed → [SourceSnapshot.Delta]. Tasks of lists that no longer exist
 *   are removed too. The next `updatedMin` is the sync start minus [CLOCK_SKEW] (overlap is harmless:
 *   applying a delta is idempotent).
 * - Mapping ([GoogleTasksMapper]): `due` → [Schedule.DateOnly] of its **date part** (Tasks stores
 *   dates as UTC midnight; no zone shift), ringing at the Google Tasks default time. Completed,
 *   deleted, hidden or undated tasks are removed. `externalId = "<listId>/<taskId>"`.
 */
class GoogleTasksSource(
    private val api: GoogleTasksApi,
    private val tokens: TokenProvider,
) : ReminderSource {
    override val type: SourceType = SourceType.GOOGLE_TASKS
    override val requiresNetwork: Boolean = true

    override suspend fun availability(): SourceAvailability =
        if (tokens.isConnected()) SourceAvailability.Ready else SourceAvailability.NotConnected

    override suspend fun snapshot(request: SyncRequest): SourceSnapshot {
        if (!tokens.isConnected()) throw NotConnectedException()
        val cursor = GoogleTasksCursor.decode(request.cursor)
        val lists = api.taskLists()
        val nextUpdatedMin = request.now.minus(CLOCK_SKEW)
        val fullDue = cursor == null || Duration.between(cursor.fullSyncAt, request.now) >= FULL_SYNC_INTERVAL
        return if (cursor == null || fullDue) {
            val dueMin = request.now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val query = TasksQuery(dueMin = RFC3339.format(dueMin))
            val reminders = lists.flatMap { list ->
                api.tasks(list.id, query).mapNotNull { GoogleTasksMapper.toReminder(list.id, it, request.zone) }
            }
            SourceSnapshot.Full(reminders, GoogleTasksCursor(nextUpdatedMin, request.now).encode())
        } else {
            val query = TasksQuery(
                showCompleted = true,
                showHidden = true,
                showDeleted = true,
                updatedMin = RFC3339.format(cursor.updatedMin),
            )
            val upserts = mutableListOf<Reminder>()
            val removed = mutableSetOf<String>()
            for (list in lists) {
                for (task in api.tasks(list.id, query)) {
                    val reminder = GoogleTasksMapper.toReminder(list.id, task, request.zone)
                    if (reminder == null) removed += GoogleTasksMapper.externalId(list.id, task.id) else upserts += reminder
                }
            }
            val liveLists = lists.mapTo(HashSet()) { it.id }
            request.knownExternalIds
                .filter { GoogleTasksMapper.listIdOf(it) !in liveLists }
                .forEach { removed += it }
            SourceSnapshot.Delta(upserts, removed, GoogleTasksCursor(nextUpdatedMin, cursor.fullSyncAt).encode())
        }
    }

    override suspend fun disconnect() = tokens.disconnect()

    companion object {
        /** Deleted lists and missed updates are reconciled by a full sync at least this often. */
        val FULL_SYNC_INTERVAL: Duration = Duration.ofHours(24)

        /** `updatedMin` overlap covering device/server clock differences. */
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

        internal val RFC3339: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}

/** Pure Google Tasks → [Reminder] mapping. */
object GoogleTasksMapper {
    const val UNTITLED: String = "Task"
    private const val SEPARATOR = '/'
    private val DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")

    fun externalId(listId: String, taskId: String): String = "$listId$SEPARATOR$taskId"

    fun listIdOf(externalId: String): String = externalId.substringBefore(SEPARATOR)

    /** The due **date** of an RFC 3339 `due` value, without any zone conversion; `null` if absent/invalid. */
    fun dueDate(due: String?): LocalDate? {
        val text = DATE_PREFIX.find(due?.trim().orEmpty())?.groupValues?.get(1) ?: return null
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeException) {
            null
        }
    }

    /** True if [task] should not ring (completed, deleted, hidden). */
    fun isInactive(task: TaskDto): Boolean = task.deleted || task.hidden || task.status == STATUS_COMPLETED

    /** The reminder for [task], or `null` if it should not exist (inactive or undated). */
    fun toReminder(listId: String, task: TaskDto, zone: ZoneId): Reminder? {
        if (isInactive(task)) return null
        val date = dueDate(task.due) ?: return null
        val externalId = externalId(listId, task.id)
        return Reminder(
            id = ReminderIds.of(SourceType.GOOGLE_TASKS, externalId),
            sourceType = SourceType.GOOGLE_TASKS,
            externalId = externalId,
            title = task.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            schedule = Schedule.DateOnly(date),
            zone = zone,
            notes = task.notes?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    const val STATUS_COMPLETED: String = "completed"
}

/** Incremental-sync state stored in `sources.syncCursor`. */
@Serializable
private data class GoogleTasksCursorDto(val updatedMin: String, val fullSyncAt: String)

internal data class GoogleTasksCursor(val updatedMin: Instant, val fullSyncAt: Instant) {
    fun encode(): String = GoogleTasksApi.JSON.encodeToString(
        GoogleTasksCursorDto.serializer(),
        GoogleTasksCursorDto(updatedMin.toString(), fullSyncAt.toString()),
    )

    companion object {
        /** Parses [text]; `null` (→ full sync) if absent or unreadable. */
        fun decode(text: String?): GoogleTasksCursor? {
            if (text.isNullOrBlank()) return null
            return try {
                val dto = GoogleTasksApi.JSON.decodeFromString(GoogleTasksCursorDto.serializer(), text)
                GoogleTasksCursor(Instant.parse(dto.updatedMin), Instant.parse(dto.fullSyncAt))
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: DateTimeException) {
                null
            }
        }
    }
}
