package app.call2remind.sources.mstodo

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
import app.call2remind.sources.http.HttpStatusException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Microsoft To Do (Graph) tasks with a reminder → [Schedule.At] reminders.
 *
 * Sync uses per-list delta queries; the cursor is a JSON map `listId → @odata.deltaLink`.
 * - No cursor → every list's initial delta (all tasks) → [SourceSnapshot.Full].
 * - Cursor → each list's delta from its link → [SourceSnapshot.Delta]. A list without a link (new)
 *   or whose link expired (**410 Gone**) is re-read from the start, and its stored tasks that are
 *   not in the fresh result are removed. Tasks of lists that disappeared are removed.
 * - Mapping ([MsTodoMapper]): `@removed`, `status = completed`, `isReminderOn != true` or no
 *   parseable `reminderDateTime` → removal. `externalId = "<listId>/<taskId>"`.
 */
class MsTodoSource(
    private val api: GraphTodoApi,
    private val tokens: TokenProvider,
) : ReminderSource {
    override val type: SourceType = SourceType.MS_TODO
    override val requiresNetwork: Boolean = true

    override suspend fun availability(): SourceAvailability =
        if (tokens.isConnected()) SourceAvailability.Ready else SourceAvailability.NotConnected

    override suspend fun snapshot(request: SyncRequest): SourceSnapshot {
        if (!tokens.isConnected()) throw NotConnectedException()
        val links = decodeCursor(request.cursor)
        val lists = api.lists()
        val newLinks = LinkedHashMap<String, String>()
        val upserts = mutableListOf<Reminder>()
        val removed = mutableSetOf<String>()
        for (list in lists) {
            val previous = links?.get(list.id)
            val delta = previous?.let { link ->
                try {
                    api.tasksDelta(list.id, link)
                } catch (e: HttpStatusException) {
                    if (e.code != HTTP_GONE) throw e
                    null
                }
            }
            val fromStart = delta == null
            val round = delta ?: api.tasksDelta(list.id, null)
            newLinks[list.id] = round.deltaLink
            val seen = HashSet<String>()
            for (task in round.tasks) {
                val externalId = MsTodoMapper.externalId(list.id, task.id)
                val reminder = MsTodoMapper.toReminder(list.id, task)
                if (reminder == null) {
                    removed += externalId
                } else {
                    upserts += reminder
                    seen += externalId
                }
            }
            if (fromStart) {
                request.knownExternalIds
                    .filter { MsTodoMapper.listIdOf(it) == list.id && it !in seen }
                    .forEach { removed += it }
            }
        }
        val cursor = encodeCursor(newLinks)
        if (links == null) return SourceSnapshot.Full(upserts.distinctBy { it.externalId }, cursor)
        val liveLists = lists.mapTo(HashSet()) { it.id }
        request.knownExternalIds
            .filter { MsTodoMapper.listIdOf(it) !in liveLists }
            .forEach { removed += it }
        val upserted = upserts.mapTo(HashSet()) { it.externalId }
        return SourceSnapshot.Delta(upserts, removed - upserted, cursor)
    }

    override suspend fun disconnect() = tokens.disconnect()

    companion object {
        private const val HTTP_GONE = 410
        private val CURSOR_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        internal fun encodeCursor(links: Map<String, String>): String =
            GraphTodoApi.JSON.encodeToString(CURSOR_SERIALIZER, links)

        /** `null` (→ full sync) if absent or unreadable. */
        internal fun decodeCursor(text: String?): Map<String, String>? {
            if (text.isNullOrBlank()) return null
            return try {
                GraphTodoApi.JSON.decodeFromString(CURSOR_SERIALIZER, text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Pure Graph To Do task → [Reminder] mapping. */
object MsTodoMapper {
    const val UNTITLED: String = "To Do task"
    const val STATUS_COMPLETED: String = "completed"
    private const val SEPARATOR = '/'

    fun externalId(listId: String, taskId: String): String = "$listId$SEPARATOR$taskId"

    fun listIdOf(externalId: String): String = externalId.substringBefore(SEPARATOR)

    /** The reminder for [task], or `null` if it must not ring (removed, completed, reminder off/invalid). */
    fun toReminder(listId: String, task: TodoTaskDto): Reminder? {
        if (task.removed != null || task.status == STATUS_COMPLETED || task.isReminderOn != true) return null
        val reminderAt = task.reminderDateTime ?: return null
        val instant = GraphTime.instant(reminderAt.dateTime, reminderAt.timeZone) ?: return null
        val externalId = externalId(listId, task.id)
        return Reminder(
            id = ReminderIds.of(SourceType.MS_TODO, externalId),
            sourceType = SourceType.MS_TODO,
            externalId = externalId,
            title = task.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            schedule = Schedule.At(instant),
            zone = GraphTime.zone(reminderAt.timeZone),
            notes = task.body?.takeIf { it.contentType.equals("text", ignoreCase = true) }
                ?.content?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
