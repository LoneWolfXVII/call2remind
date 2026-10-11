package app.call2remind.sources.google

import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.sources.http.AuthorizedHttp
import app.call2remind.testing.FakeTokenProvider
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class GoogleTasksSourceTest {
    private val server = MockWebServer().apply { start() }
    private val tokens = FakeTokenProvider("tok", "tok2")
    private val http = AuthorizedHttp(OkHttpClient(), tokens, io = Dispatchers.Unconfined, sleep = {})
    private val source = GoogleTasksSource(GoogleTasksApi(http, server.url("/tasks/v1/")), tokens)
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val fullRequest = SyncRequest(now = T0, zone = losAngeles, cursor = null, settings = SourceSettings())

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = server.enqueue(MockResponse().setBody(body.trimIndent()))

    private fun RecordedRequest.path(): String = requireNotNull(requestUrl).encodedPath

    private fun RecordedRequest.query(name: String): String? = requireNotNull(requestUrl).queryParameter(name)

    @Test
    fun isACloudSource() = runBlocking<Unit> {
        assertThat(source.type).isEqualTo(SourceType.GOOGLE_TASKS)
        assertThat(source.requiresNetwork).isTrue()
        assertThat(source.availability()).isEqualTo(SourceAvailability.Ready)
    }

    @Test
    fun notConnectedNeverCallsTheApi() {
        tokens.connected = false

        assertThat(runBlocking { source.availability() }).isEqualTo(SourceAvailability.NotConnected)
        assertThrows(NotConnectedException::class.java) { runBlocking { source.snapshot(fullRequest) } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun fullSyncPagesListsAndTasksAndMapsDueDates() = runBlocking<Unit> {
        json("""{"items":[{"id":"L1","title":"My Tasks"}],"nextPageToken":"p2"}""")
        json("""{"items":[{"id":"L2","title":"Work"}]}""")
        json(
            """
            {"items":[
              {"id":"t1","title":" Pay rent ","notes":" landlord ","status":"needsAction","due":"2026-03-12T00:00:00.000Z"},
              {"id":"t2","title":"Someday","status":"needsAction"},
              {"id":"t3","title":"Done","status":"completed","due":"2026-03-12T00:00:00.000Z"}
            ],"nextPageToken":"q2"}
            """,
        )
        json("""{"items":[{"id":"t4","title":"","due":"2026-03-20T00:00:00.000Z","unknownField":1}]}""")
        json("""{"items":[{"id":"t5","title":"Hidden","due":"2026-03-11T00:00:00.000Z","hidden":true}]}""")

        val snapshot = source.snapshot(fullRequest)

        assertThat(snapshot).isInstanceOf(SourceSnapshot.Full::class.java)
        val reminders = (snapshot as SourceSnapshot.Full).reminders
        assertThat(reminders.map { it.externalId }).containsExactly("L1/t1", "L1/t4").inOrder()
        val rent = reminders[0]
        assertThat(rent.id).isEqualTo(ReminderIds.of(SourceType.GOOGLE_TASKS, "L1/t1"))
        assertThat(rent.title).isEqualTo("Pay rent")
        assertThat(rent.notes).isEqualTo("landlord")
        // Date part only: UTC midnight of the 12th stays the 12th in Los Angeles.
        assertThat(rent.schedule).isEqualTo(Schedule.DateOnly(LocalDate.of(2026, 3, 12)))
        assertThat(rent.zone).isEqualTo(losAngeles)
        assertThat(reminders[1].title).isEqualTo(GoogleTasksMapper.UNTITLED)

        val lists1 = server.takeRequest()
        assertThat(lists1.path()).isEqualTo("/tasks/v1/users/@me/lists")
        assertThat(lists1.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(server.takeRequest().query("pageToken")).isEqualTo("p2")
        val tasks1 = server.takeRequest()
        assertThat(tasks1.path()).isEqualTo("/tasks/v1/lists/L1/tasks")
        assertThat(tasks1.query("showCompleted")).isEqualTo("false")
        assertThat(tasks1.query("showHidden")).isEqualTo("false")
        assertThat(tasks1.query("dueMin")).isEqualTo("2026-03-09T00:00:00Z")
        assertThat(tasks1.query("updatedMin")).isNull()
        assertThat(tasks1.query("pageToken")).isNull()
        assertThat(server.takeRequest().query("pageToken")).isEqualTo("q2")
        assertThat(server.takeRequest().path()).isEqualTo("/tasks/v1/lists/L2/tasks")

        assertThat(GoogleTasksCursor.decode(snapshot.cursor)).isEqualTo(GoogleTasksCursor(T0.minus(Duration.ofMinutes(5)), T0))
    }

    @Test
    fun incrementalSyncReturnsChangesAndRemovals() = runBlocking<Unit> {
        val cursor = GoogleTasksCursor(T0.minus(Duration.ofHours(1)), T0.minus(Duration.ofHours(2))).encode()
        json("""{"items":[{"id":"L1"}]}""")
        json(
            """
            {"items":[
              {"id":"t1","title":"Moved","due":"2026-03-14T00:00:00.000Z","status":"needsAction"},
              {"id":"t2","title":"Finished","due":"2026-03-14T00:00:00.000Z","status":"completed"},
              {"id":"t3","title":"Gone","deleted":true},
              {"id":"t4","title":"Due removed","status":"needsAction"}
            ]}
            """,
        )

        val snapshot = source.snapshot(
            fullRequest.copy(cursor = cursor, zone = UTC, knownExternalIds = setOf("L1/t9", "L1/t2", "OLD/t7")),
        )

        assertThat(snapshot).isInstanceOf(SourceSnapshot.Delta::class.java)
        val delta = snapshot as SourceSnapshot.Delta
        assertThat(delta.upserts.map { it.externalId to it.schedule })
            .containsExactly("L1/t1" to Schedule.DateOnly(LocalDate.of(2026, 3, 14)))
        assertThat(delta.removedExternalIds).containsExactly("L1/t2", "L1/t3", "L1/t4", "OLD/t7")
        assertThat(GoogleTasksCursor.decode(delta.cursor))
            .isEqualTo(GoogleTasksCursor(T0.minus(Duration.ofMinutes(5)), T0.minus(Duration.ofHours(2))))

        server.takeRequest()
        val tasks = server.takeRequest()
        assertThat(tasks.query("updatedMin")).isEqualTo("2026-03-10T07:00:00Z")
        assertThat(tasks.query("showCompleted")).isEqualTo("true")
        assertThat(tasks.query("showHidden")).isEqualTo("true")
        assertThat(tasks.query("showDeleted")).isEqualTo("true")
        assertThat(tasks.query("dueMin")).isNull()
    }

    @Test
    fun fullSyncAgainAfterADay() = runBlocking<Unit> {
        val cursor = GoogleTasksCursor(T0.minus(Duration.ofHours(1)), T0.minus(Duration.ofHours(25))).encode()
        json("""{"items":[]}""")

        val snapshot = source.snapshot(fullRequest.copy(cursor = cursor))

        assertThat(snapshot).isEqualTo(SourceSnapshot.Full(emptyList(), GoogleTasksCursor(T0.minus(Duration.ofMinutes(5)), T0).encode()))
    }

    @Test
    fun unreadableCursorFallsBackToFullSync() = runBlocking<Unit> {
        json("""{"items":[]}""")

        assertThat(source.snapshot(fullRequest.copy(cursor = "garbage"))).isInstanceOf(SourceSnapshot.Full::class.java)
        assertThat(GoogleTasksCursor.decode("""{"updatedMin":"x","fullSyncAt":"y"}""")).isNull()
        assertThat(GoogleTasksCursor.decode(null)).isNull()
    }

    @Test
    fun expiredTokenIsRefreshedOnce() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        json("""{"items":[]}""")

        source.snapshot(fullRequest)

        assertThat(tokens.invalidated).containsExactly("tok")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer tok2")
    }

    @Test
    fun disconnectForgetsTheAccount() = runBlocking<Unit> {
        source.disconnect()

        assertThat(tokens.disconnects).isEqualTo(1)
        assertThat(source.availability()).isEqualTo(SourceAvailability.NotConnected)
    }

    @Test
    fun dueDateTakesTheDatePartOnly() {
        assertThat(GoogleTasksMapper.dueDate("2026-03-12T00:00:00.000Z")).isEqualTo(LocalDate.of(2026, 3, 12))
        assertThat(GoogleTasksMapper.dueDate("2026-12-31T23:59:59+05:30")).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(GoogleTasksMapper.dueDate("2026-03-12")).isEqualTo(LocalDate.of(2026, 3, 12))
        assertThat(GoogleTasksMapper.dueDate("2026-02-30T00:00:00Z")).isNull()
        assertThat(GoogleTasksMapper.dueDate("soon")).isNull()
        assertThat(GoogleTasksMapper.dueDate(null)).isNull()
    }

    @Test
    fun inactiveTasksMapToNothing() {
        val due = "2026-03-12T00:00:00.000Z"
        assertThat(GoogleTasksMapper.toReminder("L", TaskDto("a", due = due, status = "completed"), UTC)).isNull()
        assertThat(GoogleTasksMapper.toReminder("L", TaskDto("b", due = due, deleted = true), UTC)).isNull()
        assertThat(GoogleTasksMapper.toReminder("L", TaskDto("c", due = due, hidden = true), UTC)).isNull()
        assertThat(GoogleTasksMapper.toReminder("L", TaskDto("d"), UTC)).isNull()
        assertThat(GoogleTasksMapper.toReminder("L", TaskDto("e", due = due), UTC)?.externalId).isEqualTo("L/e")
        assertThat(GoogleTasksMapper.listIdOf("L/e")).isEqualTo("L")
    }
}
