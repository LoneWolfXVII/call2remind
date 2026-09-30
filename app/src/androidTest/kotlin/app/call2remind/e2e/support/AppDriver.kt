package app.call2remind.e2e.support

import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import app.call2remind.Call2RemindApp
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.db.OccurrenceEntity
import app.call2remind.data.db.ReminderEntity
import app.call2remind.data.db.RingLogEntity
import app.call2remind.data.model.ReminderIds
import app.call2remind.ringing.RingNotifications
import app.call2remind.scheduling.AndroidAlarmScheduler
import app.call2remind.settings.OnboardingFlags
import app.call2remind.settings.Settings
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A reminder the test scheduled through the real engine, and its (first) occurrence. */
data class ScheduledCall(val reminder: Reminder, val occurrenceId: String, val fireAt: Instant) {
    val title: String get() = reminder.title
}

/**
 * Drives the real app graph of the process under test (instrumentation runs inside the app's
 * process, so `Call2RemindApp` is the real, Hilt-built application with its real singletons):
 * reminders go through the real `SchedulingEngine`, alarms through the real `AlarmManager`.
 *
 * The database is read through a second Room instance on the same file (reads only — every
 * state change goes through the engine, so the app's own Room instance sees and notifies it).
 */
object AppDriver {
    const val EXTERNAL_PREFIX = "e2e-"

    val app: Call2RemindApp get() = Device.context.applicationContext as Call2RemindApp

    private val db: Call2RemindDb by lazy { Call2RemindDb.create(Device.context) }

    // --- settings --------------------------------------------------------------------------

    /** Default settings with onboarding done, so nothing but the tested flow shows up. */
    fun resetSettings(transform: (Settings) -> Settings = { it }) = runBlocking {
        app.settings.update {
            transform(
                Settings(
                    onboarding = OnboardingFlags(
                        welcomeSeen = true,
                        permissionsDone = true,
                        batteryOptimizationDone = true,
                        selfTestDone = true,
                        dndHintShown = false,
                    ),
                ),
            )
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) = runBlocking { app.settings.update(transform) }

    fun settings(): Settings = runBlocking { app.settings.current() }

    // --- reminders -------------------------------------------------------------------------

    fun newTitle(label: String): String = "E2E $label ${UUID.randomUUID().toString().take(6)}"

    /** Stores a one-off `Schedule.At(now + lead)` reminder through the engine (like the self-test). */
    fun scheduleCall(label: String, lead: Duration = Duration.ofSeconds(15)): ScheduledCall {
        val fireAt = Instant.now().plus(lead).truncatedTo(ChronoUnit.SECONDS)
        val externalId = EXTERNAL_PREFIX + UUID.randomUUID()
        val reminder = Reminder(
            id = ReminderIds.of(SourceType.HABIT, externalId),
            sourceType = SourceType.HABIT,
            externalId = externalId,
            title = newTitle(label),
            schedule = Schedule.At(fireAt),
            zone = ZoneId.systemDefault(),
            notes = "End-to-end test call",
        )
        runBlocking { app.engine.upsertReminders(listOf(reminder)) }
        val id = Occurrence.scheduled(reminder, fireAt).id
        val row = Waits.value("occurrence $id stored", 5_000) { occurrence(id) }
        check(row.state == OccurrenceState.SCHEDULED) { "new occurrence is ${row.state}" }
        e2eLog("scheduled '${reminder.title}' ($id) at $fireAt")
        return ScheduledCall(reminder, id, fireAt)
    }

    fun handle(occurrenceId: String, event: OccurrenceEvent) = runBlocking { app.engine.handle(occurrenceId, event) }

    // --- database (read) -------------------------------------------------------------------

    fun occurrence(id: String): OccurrenceEntity? = runBlocking { db.occurrenceDao().get(id) }

    fun activeOccurrences(): List<OccurrenceEntity> = runBlocking { db.occurrenceDao().getActive() }

    fun activeFor(reminderId: String): List<OccurrenceEntity> = activeOccurrences().filter { it.reminderId == reminderId }

    fun reminderByTitle(title: String): ReminderEntity? =
        runBlocking { db.reminderDao().getAll() }.firstOrNull { it.title == title }

    fun ringLog(id: String): List<RingLogEntity> = runBlocking { db.ringLogDao().getForOccurrence(id) }

    fun logTypes(id: String): List<RingLogType> = ringLog(id).map { it.type }

    fun awaitState(id: String, state: OccurrenceState, timeoutMs: Long = 15_000): OccurrenceEntity =
        Waits.value("occurrence $id in state $state (is ${occurrence(id)?.state})", timeoutMs) {
            occurrence(id)?.takeIf { it.state == state }
        }

    // --- notifications ---------------------------------------------------------------------

    fun activeNotifications(): List<StatusBarNotification> = Device.notifications.activeNotifications.toList()

    fun ringNotification(occurrenceId: String): StatusBarNotification? {
        val id = app.notifications.ringId(occurrenceId)
        return activeNotifications().firstOrNull { it.id == id && it.tag == null }
    }

    fun missedNotification(occurrence: OccurrenceEntity): StatusBarNotification? =
        activeNotifications().firstOrNull { it.tag == RingNotifications.TAG_MISSED && it.id == occurrence.requestCode }

    fun dndHintNotification(): StatusBarNotification? =
        activeNotifications().firstOrNull { it.id == RingNotifications.DND_HINT_ID && it.tag == null }

    // --- alarms ----------------------------------------------------------------------------

    /** Cancels the OS alarm of [occurrenceId] behind the app's back (as a reboot would). */
    fun dropAlarm(occurrenceId: String, requestCode: Int) {
        val ctx = Device.context
        val pi = PendingIntent.getBroadcast(
            ctx,
            requestCode,
            AndroidAlarmScheduler.fireIntent(ctx, occurrenceId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            Device.alarms.cancel(pi)
            pi.cancel()
        }
        e2eLog("dropped alarm of $occurrenceId (pendingIntent existed=${pi != null})")
    }

    fun nextAlarmClockAt(): Instant? = Device.alarms.nextAlarmClock?.triggerTime?.let(Instant::ofEpochMilli)

    // --- cleanup ---------------------------------------------------------------------------

    /**
     * Ends every active occurrence (Done, through the engine, so the ringing service and call
     * screen close), removes the test's reminders and waits until nothing rings.
     */
    fun cleanUp() {
        runCatching {
            for (row in activeOccurrences()) {
                runCatching { handle(row.id, OccurrenceEvent.Done) }
            }
            val ours = runBlocking { db.reminderDao().getAll() }.filter { it.externalId.startsWith(EXTERNAL_PREFIX) }.map { it.id }
            if (ours.isNotEmpty()) runBlocking { app.engine.deleteReminders(ours) }
        }.onFailure { e2eLog("cleanup of occurrences failed: $it") }
        Waits.within(15_000) { !Device.isRingingServiceRunning() && !Device.isCallActivityAlive() }
            .also { idle -> if (!idle) e2eLog("ringing service / call screen still alive after cleanup") }
        Device.notifications.cancelAll()
    }

    /** One line per active occurrence and its log, for failure diagnostics. */
    fun describeState(): String = buildString {
        val rows = runCatching { runBlocking { db.occurrenceDao().getActive() } }.getOrDefault(emptyList())
        appendLine("active occurrences: ${rows.size}")
        for (row in rows) {
            appendLine("  ${row.id} state=${row.state} fireAt=${row.fireAt} ringBacks=${row.ringBacks} answeredAt=${row.answeredAt}")
            for (log in ringLog(row.id)) appendLine("    log ${log.type} ${log.timestamp} ${log.reason ?: ""}")
        }
    }
}
