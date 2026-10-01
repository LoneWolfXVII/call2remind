package app.call2remind.sources.http

import app.call2remind.core.sync.Backoff
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.RetryableSyncException
import app.call2remind.testing.FakeTokenProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AuthorizedHttpTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val sleeps = mutableListOf<Duration>()
    private val url = server.url("/v1/items")

    private fun http(tokens: FakeTokenProvider, maxAttempts: Int = 3) = AuthorizedHttp(
        client = client,
        tokens = tokens,
        backoff = Backoff(Duration.ofSeconds(1), Duration.ofSeconds(8), random = Random(42)),
        maxAttempts = maxAttempts,
        io = Dispatchers.Unconfined,
        sleep = { sleeps += it },
    )

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsBearerTokenAndReturnsTheBody() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val body = http(FakeTokenProvider("t1")).get(url)

        assertThat(body).isEqualTo("""{"ok":true}""")
        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer t1")
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun extraHeadersAreSent() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))

        http(FakeTokenProvider("t1")).get(url, mapOf("Prefer" to "odata.maxpagesize=50"))

        assertThat(server.takeRequest().getHeader("Prefer")).isEqualTo("odata.maxpagesize=50")
    }

    @Test
    fun unauthorizedInvalidatesTheTokenAndRetriesOnce() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("fresh"))
        val tokens = FakeTokenProvider("stale", "fresh-token")

        val body = http(tokens).get(url)

        assertThat(body).isEqualTo("fresh")
        assertThat(tokens.invalidated).containsExactly("stale")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer stale")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh-token")
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun secondUnauthorizedMeansNotConnected() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val tokens = FakeTokenProvider("a", "b")

        assertThrows(NotConnectedException::class.java) { runBlocking { http(tokens).get(url) } }
        assertThat(tokens.invalidated).containsExactly("a")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun noTokenMeansNotConnectedWithoutARequest() {
        assertThrows(NotConnectedException::class.java) {
            runBlocking { http(FakeTokenProvider("t", connected = false)).get(url) }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun tooManyRequestsHonoursRetryAfter() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(MockResponse().setBody("ok"))

        assertThat(http(FakeTokenProvider("t")).get(url)).isEqualTo("ok")
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7))
    }

    @Test
    fun retryAfterIsCapped() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
        server.enqueue(MockResponse().setBody("ok"))

        http(FakeTokenProvider("t")).get(url)

        assertThat(sleeps).containsExactly(Duration.ofSeconds(60))
    }

    @Test
    fun serverErrorsBackOffThenGiveUpAsRetryable() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val e = assertThrows(RetryableSyncException::class.java) { runBlocking { http(FakeTokenProvider("t")).get(url) } }

        assertThat(e.message).contains("503")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(sleeps).hasSize(2)
        assertThat(sleeps[0]).isAtMost(Duration.ofSeconds(1))
        assertThat(sleeps[1]).isAtMost(Duration.ofSeconds(2))
    }

    @Test
    fun serverErrorThenSuccessRecovers() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("ok"))

        assertThat(http(FakeTokenProvider("t")).get(url)).isEqualTo("ok")
        assertThat(sleeps).hasSize(2)
    }

    @Test
    fun forbiddenRateLimitIsRetriedButOtherForbiddenIsNot() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}"""))
        server.enqueue(MockResponse().setBody("ok"))
        assertThat(http(FakeTokenProvider("t")).get(url)).isEqualTo("ok")

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        val e = assertThrows(HttpStatusException::class.java) { runBlocking { http(FakeTokenProvider("t")).get(url) } }
        assertThat(e.code).isEqualTo(403)
    }

    @Test
    fun otherClientErrorsAreNotRetried() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))

        val e = assertThrows(HttpStatusException::class.java) { runBlocking { http(FakeTokenProvider("t")).get(url) } }

        assertThat(e.code).isEqualTo(404)
        assertThat(e.body).isEqualTo("nope")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun networkFailuresAreRetriedThenRetryable() {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        assertThrows(RetryableSyncException::class.java) {
            runBlocking { http(FakeTokenProvider("t"), maxAttempts = 2).get(url) }
        }
        assertThat(sleeps).hasSize(1)
    }
}
