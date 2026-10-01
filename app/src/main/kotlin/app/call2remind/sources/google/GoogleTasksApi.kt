package app.call2remind.sources.google

import app.call2remind.sources.PermanentSyncException
import app.call2remind.sources.http.AuthorizedHttp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class TaskListDto(val id: String, val title: String? = null)

@Serializable
data class TaskDto(
    val id: String,
    val title: String? = null,
    val notes: String? = null,
    /** `needsAction` or `completed`. */
    val status: String? = null,
    /** RFC 3339 timestamp whose **date** part is the due date (the time is always discarded). */
    val due: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val updated: String? = null,
)

@Serializable
internal data class TaskListsPage(val items: List<TaskListDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class TasksPage(val items: List<TaskDto> = emptyList(), val nextPageToken: String? = null)

/** Query flags for [GoogleTasksApi.tasks]. Timestamps are RFC 3339 strings. */
data class TasksQuery(
    val showCompleted: Boolean = false,
    val showHidden: Boolean = false,
    val showDeleted: Boolean = false,
    val dueMin: String? = null,
    val updatedMin: String? = null,
)

/**
 * Google Tasks REST API v1 (`tasks.readonly`), read-only, following `nextPageToken` paging.
 * Auth, 401 re-auth and 429/5xx backoff are handled by [AuthorizedHttp].
 */
class GoogleTasksApi(
    private val http: AuthorizedHttp,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    /** `GET users/@me/lists`, all pages. */
    suspend fun taskLists(): List<TaskListDto> = paged(TaskListsPage.serializer(), { it.items }, { it.nextPageToken }) { pageToken ->
        baseUrl.newBuilder()
            .addPathSegments("users/@me/lists")
            .addQueryParameter("maxResults", MAX_RESULTS.toString())
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
    }

    /** `GET lists/{listId}/tasks`, all pages. */
    suspend fun tasks(listId: String, query: TasksQuery): List<TaskDto> =
        paged(TasksPage.serializer(), { it.items }, { it.nextPageToken }) { pageToken ->
            baseUrl.newBuilder()
                .addPathSegment("lists")
                .addPathSegment(listId)
                .addPathSegment("tasks")
                .addQueryParameter("maxResults", MAX_RESULTS.toString())
                .addQueryParameter("showCompleted", query.showCompleted.toString())
                .addQueryParameter("showHidden", query.showHidden.toString())
                .addQueryParameter("showDeleted", query.showDeleted.toString())
                .apply {
                    query.dueMin?.let { addQueryParameter("dueMin", it) }
                    query.updatedMin?.let { addQueryParameter("updatedMin", it) }
                    pageToken?.let { addQueryParameter("pageToken", it) }
                }
                .build()
        }

    private suspend fun <P, T> paged(
        serializer: KSerializer<P>,
        items: (P) -> List<T>,
        next: (P) -> String?,
        url: (pageToken: String?) -> HttpUrl,
    ): List<T> {
        val result = mutableListOf<T>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val page = decode(serializer, http.get(url(pageToken)))
            result += items(page)
            pageToken = next(page)?.takeIf { it.isNotEmpty() } ?: return result
        }
        throw PermanentSyncException("Google Tasks paging did not end after $MAX_PAGES pages")
    }

    private fun <P> decode(serializer: KSerializer<P>, body: String): P = try {
        JSON.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw PermanentSyncException("Malformed Google Tasks response", e)
    } catch (e: IllegalArgumentException) {
        throw PermanentSyncException("Malformed Google Tasks response", e)
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://tasks.googleapis.com/tasks/v1/"
        const val MAX_RESULTS: Int = 100
        const val MAX_PAGES: Int = 100
        internal val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
