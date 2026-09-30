package app.call2remind.sources.http

import app.call2remind.core.sync.Backoff
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.PermanentSyncException
import app.call2remind.sources.RetryableSyncException
import app.call2remind.sources.auth.TokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration

/** A non-retryable HTTP error status (e.g. 404, or 410 for an expired Graph delta token). */
class HttpStatusException(val code: Int, val body: String?) : PermanentSyncException("HTTP $code")

/**
 * Authorized JSON GETs with the retry rules every cloud source shares:
 * - `Authorization: Bearer <token>` from [tokens]; no token → [NotConnectedException].
 * - **401** → [TokenProvider.invalidate] + fetch a new token + retry **once**; a second 401 →
 *   [NotConnectedException] (the user must reconnect).
 * - **429 / 5xx / 403 rate-limit / network errors** → wait (`Retry-After` seconds if present,
 *   capped at [maxRetryAfter], else [backoff]) and retry, up to [maxAttempts] attempts in total;
 *   then [RetryableSyncException] so the worker retries later.
 * - Any other non-2xx → [HttpStatusException].
 */
class AuthorizedHttp(
    private val client: OkHttpClient,
    private val tokens: TokenProvider,
    private val backoff: Backoff = Backoff(base = Duration.ofSeconds(1), cap = Duration.ofSeconds(30)),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxRetryAfter: Duration = Duration.ofSeconds(60),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    private class Raw(val code: Int, val body: String?, val retryAfter: String?)

    /** GETs [url] and returns the response body of a 2xx response. */
    suspend fun get(url: HttpUrl, headers: Map<String, String> = emptyMap()): String {
        var token = tokens.accessToken() ?: throw NotConnectedException()
        var reauthorized = false
        var attempt = 0
        while (true) {
            val raw = try {
                execute(url, token, headers)
            } catch (e: IOException) {
                if (attempt + 1 >= maxAttempts) throw RetryableSyncException("Network error: ${e.message}", e)
                sleep(backoff.delayFor(attempt))
                attempt++
                continue
            }
            when {
                raw.code in HTTP_OK_RANGE -> return raw.body.orEmpty()
                raw.code == HTTP_UNAUTHORIZED -> {
                    if (reauthorized) throw NotConnectedException("Authorization rejected (HTTP 401)")
                    reauthorized = true
                    tokens.invalidate(token)
                    token = tokens.accessToken() ?: throw NotConnectedException()
                }
                isRetryable(raw) -> {
                    if (attempt + 1 >= maxAttempts) throw RetryableSyncException("HTTP ${raw.code}")
                    sleep(retryAfter(raw.retryAfter) ?: backoff.delayFor(attempt))
                    attempt++
                }
                else -> throw HttpStatusException(raw.code, raw.body)
            }
        }
    }

    private suspend fun execute(url: HttpUrl, token: String, headers: Map<String, String>): Raw = withContext(io) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            Raw(response.code, response.body?.string(), response.header("Retry-After"))
        }
    }

    private fun isRetryable(raw: Raw): Boolean =
        raw.code == HTTP_TOO_MANY_REQUESTS ||
            raw.code >= HTTP_SERVER_ERROR ||
            (raw.code == HTTP_FORBIDDEN && raw.body?.contains("rateLimitExceeded", ignoreCase = true) == true)

    private fun retryAfter(header: String?): Duration? {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return minOf(Duration.ofSeconds(seconds), maxRetryAfter)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
