package app.call2remind.sources.mstodo

import app.call2remind.sources.PermanentSyncException
import app.call2remind.sources.http.AuthorizedHttp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class TodoListDto(val id: String, val displayName: String? = null)

@Serializable
data class DateTimeTimeZoneDto(val dateTime: String? = null, val timeZone: String? = null)

@Serializable
data class ItemBodyDto(val content: String? = null, val contentType: String? = null)

@Serializable
data class TodoTaskDto(
    val id: String,
    val title: String? = null,
    /** `notStarted`, `inProgress`, `completed`, `waitingOnOthers`, `deferred`. */
    val status: String? = null,
    val isReminderOn: Boolean? = null,
    val reminderDateTime: DateTimeTimeZoneDto? = null,
    val body: ItemBodyDto? = null,
    /** Present (e.g. `{"reason":"deleted"}`) when a delta reports the task as removed. */
    @SerialName("@removed") val removed: JsonObject? = null,
)

@Serializable
internal data class TodoListsPage(
    val value: List<TodoListDto> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

@Serializable
internal data class TodoTasksPage(
    val value: List<TodoTaskDto> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
)

/** One list's delta round: the changed tasks and the `@odata.deltaLink` for next time. */
data class TodoDelta(val tasks: List<TodoTaskDto>, val deltaLink: String)

/**
 * Microsoft Graph To Do endpoints (read-only). Auth and retries: [AuthorizedHttp]. An expired
 * delta token surfaces as [app.call2remind.sources.http.HttpStatusException] with code 410.
 */
class GraphTodoApi(
    private val http: AuthorizedHttp,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    /** `GET me/todo/lists`, following `@odata.nextLink`. */
    suspend fun lists(): List<TodoListDto> {
        val result = mutableListOf<TodoListDto>()
        var url: HttpUrl? = baseUrl.newBuilder().addPathSegments("me/todo/lists").build()
        var pages = 0
        while (url != null) {
            if (++pages > MAX_PAGES) throw PermanentSyncException("Graph paging did not end after $MAX_PAGES pages")
            val page = decode(TodoListsPage.serializer(), http.get(url))
            result += page.value
            url = page.nextLink?.let(::link)
        }
        return result
    }

    /**
     * Runs one delta round for [listId]: from [deltaLink] if given, else from the start
     * (`me/todo/lists/{id}/tasks/delta`, which returns every task). Follows `@odata.nextLink`
     * until a page carries `@odata.deltaLink`.
     */
    suspend fun tasksDelta(listId: String, deltaLink: String?): TodoDelta {
        var url: HttpUrl = deltaLink?.let(::link) ?: baseUrl.newBuilder()
            .addPathSegments("me/todo/lists")
            .addPathSegment(listId)
            .addPathSegments("tasks/delta")
            .build()
        val tasks = mutableListOf<TodoTaskDto>()
        repeat(MAX_PAGES) {
            val page = decode(TodoTasksPage.serializer(), http.get(url))
            tasks += page.value
            page.deltaLink?.let { return TodoDelta(tasks, it) }
            url = page.nextLink?.let(::link) ?: throw PermanentSyncException("Graph delta page without nextLink or deltaLink")
        }
        throw PermanentSyncException("Graph delta paging did not end after $MAX_PAGES pages")
    }

    private fun link(text: String): HttpUrl =
        text.toHttpUrlOrNull() ?: throw PermanentSyncException("Malformed Graph link")

    private fun <P> decode(serializer: KSerializer<P>, body: String): P = try {
        JSON.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw PermanentSyncException("Malformed Graph response", e)
    } catch (e: IllegalArgumentException) {
        throw PermanentSyncException("Malformed Graph response", e)
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://graph.microsoft.com/v1.0/"
        const val MAX_PAGES: Int = 200
        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
