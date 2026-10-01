package app.call2remind.testing

import android.os.Looper
import app.cash.turbine.ReceiveTurbine
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.model.ReminderIds
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Fixed test epoch: Tuesday 2026-03-10 08:00 UTC. */
val T0: Instant = Instant.parse("2026-03-10T08:00:00Z")

val UTC: ZoneId = ZoneOffset.UTC

fun minutes(n: Long): Duration = Duration.ofMinutes(n)

fun hours(n: Long): Duration = Duration.ofHours(n)

/** A [Clock] tests move by hand. Thread-safe. */
class MutableClock(
    now: Instant = T0,
    zone: ZoneId = UTC,
) : Clock() {
    @Volatile
    var now: Instant = now

    /** The zone [getZone] returns; tests change it to simulate the device changing time zone. */
    @Volatile
    var currentZone: ZoneId = zone

    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** A reminder whose id follows [ReminderIds]. */
fun reminder(
    externalId: String = "r1",
    schedule: Schedule = Schedule.At(T0.plus(hours(1))),
    sourceType: SourceType = SourceType.HABIT,
    title: String = "Reminder $externalId",
    notes: String? = null,
    zone: ZoneId = UTC,
    enabled: Boolean = true,
    ringtoneUri: String? = null,
    ttsEnabled: Boolean = true,
): Reminder = Reminder(
    id = ReminderIds.of(sourceType, externalId),
    sourceType = sourceType,
    externalId = externalId,
    title = title,
    schedule = schedule,
    zone = zone,
    notes = notes,
    ringtoneUri = ringtoneUri,
    ttsEnabled = ttsEnabled,
    enabled = enabled,
)

/** An occurrence of [reminder] planned at [plannedAt]. */
fun occurrence(
    reminder: Reminder,
    plannedAt: Instant,
    state: OccurrenceState = OccurrenceState.SCHEDULED,
    fireAt: Instant = plannedAt,
    ringBacks: Int = 0,
    answeredAt: Instant? = null,
): Occurrence = Occurrence.scheduled(reminder, plannedAt).copy(
    state = state,
    fireAt = fireAt,
    ringBacks = ringBacks,
    answeredAt = answeredAt,
)

/** Fresh in-memory database (Robolectric). */
fun newTestDb(): Call2RemindDb =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Call2RemindDb::class.java).build()

/**
 * Polls [condition] (idling the Robolectric main looper between checks) until it holds or
 * [timeoutMs] elapses. For work that hops between Room's executors and the main thread.
 */
fun awaitUntil(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $message")
        Thread.sleep(POLL_MS)
    }
}

private const val POLL_MS = 10L

/** Skips emissions until one matches [predicate] (Room may re-emit unchanged results). */
suspend fun <T> ReceiveTurbine<T>.awaitItemMatching(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
