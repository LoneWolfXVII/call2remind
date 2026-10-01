package app.call2remind.sources.mstodo

import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.PermanentSyncException
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MsTodoSourceTest {
    private val server = MockWebServer().apply { start() }
    private val tokens = FakeTokenProvider("tok")
    private val http = AuthorizedHttp(OkHttpClient(), tokens, io = Dispatchers.Unconfined, sleep = {})
    private val source = MsTodoSource(GraphTodoApi(http, server.url("/v1.0/")), tokens)
    private val request = SyncRequest(now = T0, zone = UTC, cursor = null, settings = SourceSettings())

    @After
    fun tearDown() = server.shutdown()

    private fun link(path: String): String = server.url(path).toString()

    private fun json(body: String) = server.enqueue(MockResponse().setBody(body.trimIndent()))

    private fun RecordedRequest.path(): String = requireNotNull(requestUrl).encodedPath

    private fun task(id: String, at: String, zone: String = "UTC", extra: String = "") =
        """{"id":"$id","title":"Task $id","status":"notStarted","isReminderOn":true,"reminderDateTime":{"dateTime":"$at","timeZone":"$zone"}$extra}"""

    @Test
    fun isACloudSourceGatedByTheToken() = runBlocking<Unit> {
        assertThat(source.type).isEqualTo(SourceType.MS_TODO)
        assertThat(source.requiresNetwork).isTrue()
        assertThat(source.availability()).isEqualTo(SourceAvailability.Ready)
        tokens.connected = false
        assertThat(source.availability()).isEqualTo(SourceAvailability.NotConnected)
        assertThrows(NotConnectedException::class.java) { runBlocking { source.snapshot(request) } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun initialSyncFollowsNextLinksAndReturnsAFullSnapshot() = runBlocking<Unit> {
        json("""{"value":[{"id":"A","displayName":"Tasks"}],"@odata.nextLink":"${link("/v1.0/me/todo/lists?skip=1")}"}""")
        json("""{"value":[{"id":"B","displayName":"Work"}]}""")
        json(
            """{"value":[${task("a1", "2026-03-10T09:00:00.0000000", "India Standard Time")}],
               "@odata.nextLink":"${link("/v1.0/me/todo/lists/A/tasks/delta?skiptoken=x")}"}""",
        )
        json(
            """{"value":[
                 ${task("a2", "2026-03-10T09:00:00.0000000", extra = ",\"status\":\"completed\"").replace("\"status\":\"notStarted\",", "")},
                 {"id":"a3","title":"No reminder","isReminderOn":false,"reminderDateTime":{"dateTime":"2026-03-10T09:00:00.0000000","timeZone":"UTC"}},
                 {"id":"a4","title":"Reminder on but no time","isReminderOn":true}
               ],
               "@odata.deltaLink":"${link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA")}"}""",
        )
        json(
            """{"value":[${task("b1", "2026-03-11T10:30:00.1234567", extra = ",\"body\":{\"content\":\" bring slides \",\"contentType\":\"text\"}")}],
               "@odata.deltaLink":"${link("/v1.0/me/todo/lists/B/tasks/delta?deltatoken=dB")}"}""",
        )

        val snapshot = source.snapshot(request)

        assertThat(snapshot).isInstanceOf(SourceSnapshot.Full::class.java)
        val reminders = (snapshot as SourceSnapshot.Full).reminders.associateBy { it.externalId }
        assertThat(reminders.keys).containsExactly("A/a1", "B/b1")
        val a1 = reminders.getValue("A/a1")
        assertThat(a1.id).isEqualTo(ReminderIds.of(SourceType.MS_TODO, "A/a1"))
        assertThat(a1.title).isEqualTo("Task a1")
        assertThat(a1.schedule).isEqualTo(Schedule.At(Instant.parse("2026-03-10T03:30:00Z")))
        assertThat(a1.zone).isEqualTo(ZoneId.of("Asia/Kolkata"))
        val b1 = reminders.getValue("B/b1")
        assertThat(b1.schedule).isEqualTo(Schedule.At(Instant.parse("2026-03-11T10:30:00.1234567Z")))
        assertThat(b1.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(b1.notes).isEqualTo("bring slides")

        assertThat(MsTodoSource.decodeCursor(snapshot.cursor)).containsExactly(
            "A", link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA"),
            "B", link("/v1.0/me/todo/lists/B/tasks/delta?deltatoken=dB"),
        )
        assertThat(server.takeRequest().path()).isEqualTo("/v1.0/me/todo/lists")
        assertThat(server.takeRequest().requestUrl?.queryParameter("skip")).isEqualTo("1")
        assertThat(server.takeRequest().path()).isEqualTo("/v1.0/me/todo/lists/A/tasks/delta")
        assertThat(server.takeRequest().requestUrl?.queryParameter("skiptoken")).isEqualTo("x")
        assertThat(server.takeRequest().path()).isEqualTo("/v1.0/me/todo/lists/B/tasks/delta")
    }

    @Test
    fun deltaSyncAppliesChangesRemovalsExpiredTokensAndVanishedLists() = runBlocking<Unit> {
        val cursor = MsTodoSource.encodeCursor(
            mapOf(
                "A" to link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA"),
                "B" to link("/v1.0/me/todo/lists/B/tasks/delta?deltatoken=dB"),
                "C" to link("/v1.0/me/todo/lists/C/tasks/delta?deltatoken=dC"),
            ),
        )
        json("""{"value":[{"id":"A"},{"id":"B"}]}""")
        json(
            """{"value":[
                 {"id":"a1","@removed":{"reason":"deleted"}},
                 {"id":"a2","title":"Done now","status":"completed","isReminderOn":true,"reminderDateTime":{"dateTime":"2026-03-12T09:00:00.0000000","timeZone":"UTC"}},
                 ${task("a6", "2026-03-12T08:00:00.0000000", "Pacific Standard Time")}
               ],
               "@odata.deltaLink":"${link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA2")}"}""",
        )
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":{"code":"syncStateNotFound"}}"""))
        json(
            """{"value":[${task("b2", "2026-03-13T08:00:00.0000000")}],
               "@odata.deltaLink":"${link("/v1.0/me/todo/lists/B/tasks/delta?deltatoken=dB2")}"}""",
        )

        val snapshot = source.snapshot(
            request.copy(cursor = cursor, knownExternalIds = setOf("A/a1", "A/a2", "A/a5", "B/b1", "B/b2", "C/c1")),
        )

        assertThat(snapshot).isInstanceOf(SourceSnapshot.Delta::class.java)
        val delta = snapshot as SourceSnapshot.Delta
        assertThat(delta.upserts.map { it.externalId }).containsExactly("A/a6", "B/b2")
        assertThat(delta.upserts.first().schedule).isEqualTo(Schedule.At(Instant.parse("2026-03-12T15:00:00Z")))
        // a5 is unchanged (not in A's delta); b1 vanished while B was re-read from scratch; C is gone.
        assertThat(delta.removedExternalIds).containsExactly("A/a1", "A/a2", "B/b1", "C/c1")
        assertThat(MsTodoSource.decodeCursor(delta.cursor)).containsExactly(
            "A", link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA2"),
            "B", link("/v1.0/me/todo/lists/B/tasks/delta?deltatoken=dB2"),
        )

        server.takeRequest()
        assertThat(server.takeRequest().requestUrl?.queryParameter("deltatoken")).isEqualTo("dA")
        assertThat(server.takeRequest().requestUrl?.queryParameter("deltatoken")).isEqualTo("dB")
        val restart = server.takeRequest()
        assertThat(restart.path()).isEqualTo("/v1.0/me/todo/lists/B/tasks/delta")
        assertThat(restart.requestUrl?.queryParameter("deltatoken")).isNull()
    }

    @Test
    fun newListInDeltaModeIsReadFromTheStart() = runBlocking<Unit> {
        val cursor = MsTodoSource.encodeCursor(mapOf("A" to link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA")))
        json("""{"value":[{"id":"A"},{"id":"N"}]}""")
        json("""{"value":[],"@odata.deltaLink":"${link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA2")}"}""")
        json("""{"value":[${task("n1", "2026-03-12T08:00:00")}],"@odata.deltaLink":"${link("/v1.0/me/todo/lists/N/tasks/delta?deltatoken=dN")}"}""")

        val delta = source.snapshot(request.copy(cursor = cursor, knownExternalIds = setOf("A/a1"))) as SourceSnapshot.Delta

        assertThat(delta.upserts.map { it.externalId }).containsExactly("N/n1")
        assertThat(delta.removedExternalIds).isEmpty()
    }

    @Test
    fun otherHttpErrorsPropagate() {
        val cursor = MsTodoSource.encodeCursor(mapOf("A" to link("/v1.0/me/todo/lists/A/tasks/delta?deltatoken=dA")))
        json("""{"value":[{"id":"A"}]}""")
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(PermanentSyncException::class.java) { runBlocking { source.snapshot(request.copy(cursor = cursor)) } }
    }

    @Test
    fun malformedResponsesArePermanentErrors() {
        json("""not json""")

        assertThrows(PermanentSyncException::class.java) { runBlocking { source.snapshot(request) } }
    }

    @Test
    fun deltaPageWithoutAnyLinkIsAnError() {
        json("""{"value":[{"id":"A"}]}""")
        json("""{"value":[]}""")

        assertThrows(PermanentSyncException::class.java) { runBlocking { source.snapshot(request) } }
    }

    @Test
    fun unreadableCursorMeansFullSync() {
        assertThat(MsTodoSource.decodeCursor("{broken")).isNull()
        assertThat(MsTodoSource.decodeCursor(null)).isNull()
        assertThat(MsTodoSource.decodeCursor(MsTodoSource.encodeCursor(mapOf("x" to "y")))).containsExactly("x", "y")
    }

    @Test
    fun mapperRules() {
        val on = TodoTaskDto("t", "T", "notStarted", true, DateTimeTimeZoneDto("2026-03-10T09:00:00.0000000", "UTC"))
        assertThat(MsTodoMapper.toReminder("L", on)?.externalId).isEqualTo("L/t")
        assertThat(MsTodoMapper.toReminder("L", on.copy(isReminderOn = null))).isNull()
        assertThat(MsTodoMapper.toReminder("L", on.copy(status = "completed"))).isNull()
        assertThat(MsTodoMapper.toReminder("L", on.copy(reminderDateTime = DateTimeTimeZoneDto("garbage", "UTC")))).isNull()
        assertThat(MsTodoMapper.toReminder("L", on.copy(title = " "))?.title).isEqualTo(MsTodoMapper.UNTITLED)
        assertThat(MsTodoMapper.toReminder("L", on.copy(body = ItemBodyDto("<p>html</p>", "html")))?.notes).isNull()
    }
}
