package app.call2remind.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.LocalCalendar
import app.call2remind.e2e.support.Waits
import app.call2remind.e2e.support.e2eLog
import app.call2remind.receivers.BootReceiver
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Losing alarms and moving clocks: BOOT_COMPLETED re-arms alarms the OS dropped, and a time zone
 * change keeps date-only reminders and habits at their wall-clock time in the new zone.
 * (Process death with a pending alarm is covered by [ProcessDeathPhases], driven from the shell.)
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RecoveryE2eTest {
    @get:Rule
    val e2e = E2eRule()

    @Test
    fun bootCompletedRearmsDroppedAlarms() {
        val call = AppDriver.scheduleCall("boot", lead = Duration.ofMinutes(10))
        val row = AppDriver.occurrence(call.occurrenceId)!!
        Waits.until("alarm clock armed at ${call.fireAt}", 5_000) { AppDriver.nextAlarmClockAt() == call.fireAt }
        val armedBefore = Device.pendingAppAlarmCount()
        assertWithMessage("our alarms in dumpsys alarm").that(armedBefore).isAtLeast(1)

        // What a reboot does to our alarms: they are simply gone.
        AppDriver.dropAlarm(call.occurrenceId, row.requestCode)
        Waits.until("alarm dropped", 5_000) { AppDriver.nextAlarmClockAt() != call.fireAt }
        assertThat(Device.pendingAppAlarmCount()).isLessThan(armedBefore)

        val out = Device.shell("am broadcast -a android.intent.action.BOOT_COMPLETED -n app.call2remind/.receivers.BootReceiver")
        e2eLog("am broadcast BOOT_COMPLETED: ${out.trim()}")
        if (!out.contains("Broadcast completed")) {
            // Without root the shell may not send a protected broadcast to a non-exported
            // receiver; deliver the same intent to the receiver directly instead.
            e2eLog("shell broadcast refused (root=${Device.isRoot}); invoking BootReceiver directly")
            Device.instrumentation.runOnMainSync {
                BootReceiver().onReceive(Device.context, Intent(Intent.ACTION_BOOT_COMPLETED))
            }
        }

        Waits.until("alarm re-armed after BOOT_COMPLETED", 15_000) { AppDriver.nextAlarmClockAt() == call.fireAt }
        assertWithMessage("our alarms in dumpsys alarm after boot").that(Device.pendingAppAlarmCount()).isAtLeast(armedBefore)
        e2eLog(Device.alarmDumpForApp())
        val after = AppDriver.occurrence(call.occurrenceId)!!
        assertThat(after.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(after.fireAt).isEqualTo(call.fireAt)
    }

    @Test
    fun timeZoneChangeKeepsAllDayEventAtLocalWallTime() {
        val original = Device.deviceTimeZone().ifBlank { "GMT" }
        e2e.onTearDown("restore time zone $original") { Device.setTimeZone(original) }
        e2e.onTearDown("delete test calendar") {
            LocalCalendar.deleteAll()
            AppDriver.app.calendarObserver.onCalendarChanged()
        }
        assumeTrue("could not set the time zone to $ZONE_A", Device.setTimeZone(ZONE_A))

        val calendarId = LocalCalendar.create()
        val title = AppDriver.newTitle("all-day")
        val date = LocalDate.now(ZoneId.of(ZONE_A)).plusDays(1)
        LocalCalendar.insertAllDayEvent(calendarId, title, date)
        AppDriver.app.calendarObserver.ensureRegistered()
        AppDriver.app.calendarObserver.onCalendarChanged()

        val reminder = Waits.value("calendar sync stored '$title'", 30_000) { AppDriver.reminderByTitle(title) }
        assertThat(reminder.sourceType).isEqualTo(SourceType.CALENDAR)
        val time = AppDriver.settings().defaultTimes.calendarAllDay
        val atA = date.atTime(time).atZone(ZoneId.of(ZONE_A)).toInstant()
        val plannedInA = Waits.within(15_000) { AppDriver.activeFor(reminder.id).any { it.fireAt == atA } }
        assertWithMessage("occurrence at $date $time in $ZONE_A ($atA); active: ${AppDriver.activeFor(reminder.id).map { it.fireAt }}")
            .that(plannedInA).isTrue()

        assumeTrue("could not set the time zone to $ZONE_B", Device.setTimeZone(ZONE_B))
        val atB = date.atTime(time).atZone(ZoneId.of(ZONE_B)).toInstant()
        // The receiver's own replan moves it (no sync needed), so this is quick.
        val moved = Waits.within(20_000) { AppDriver.activeFor(reminder.id).map { it.fireAt } == listOf(atB) }
        assertWithMessage(
            "after the zone change the occurrence rings at $date $time in $ZONE_B ($atB), not $atA; " +
                "active: ${AppDriver.activeFor(reminder.id).map { it.fireAt }}, reminder zone now ${AppDriver.reminderByTitle(title)?.zoneId}",
        ).that(moved).isTrue()
        assertThat(AppDriver.reminderByTitle(title)?.zoneId).isEqualTo(ZONE_B)
        // The row moves first and its alarm is armed just after (old one cancelled, new one armed):
        // wait for the alarm itself rather than sampling dumpsys once.
        awaitAlarmMoved(from = atA, to = atB)
    }

    @Test
    fun timeZoneChangeKeepsHabitAtLocalWallTime() {
        val original = Device.deviceTimeZone().ifBlank { "GMT" }
        e2e.onTearDown("restore time zone $original") { Device.setTimeZone(original) }
        assumeTrue("could not set the time zone to $ZONE_A", Device.setTimeZone(ZONE_A))
        val zoneA = ZoneId.of(ZONE_A)
        val zoneB = ZoneId.of(ZONE_B)
        // An hour from now in A; the same wall time in B is 13.5 h (PDT) / 14.5 h (PST) away, so
        // the habit never rings during the test.
        val wall = LocalTime.now(zoneA).plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        val habit = AppDriver.scheduleDailyHabit("habit tz", wall)
        assertThat(habit.zone).isEqualTo(zoneA)
        val nextInA = nextAt(wall, zoneA)
        Waits.until("habit planned at $wall $ZONE_A ($nextInA)", 10_000) {
            AppDriver.activeFor(habit.id).minOfOrNull { it.fireAt } == nextInA
        }

        assumeTrue("could not set the time zone to $ZONE_B", Device.setTimeZone(ZONE_B))
        val nextInB = nextAt(wall, zoneB)
        val moved = Waits.within(20_000) { AppDriver.activeFor(habit.id).minOfOrNull { it.fireAt } == nextInB }
        val active = AppDriver.activeFor(habit.id).map { it.fireAt }
        assertWithMessage("habit keeps its wall time $wall in $ZONE_B (next $nextInB); active: $active").that(moved).isTrue()
        assertWithMessage("every ring at $wall local in $ZONE_B; active: $active")
            .that(active.map { it.atZone(zoneB).toLocalTime() }.distinct()).containsExactly(wall)
        assertThat(AppDriver.reminderByTitle(habit.title)?.zoneId).isEqualTo(ZONE_B)
        awaitAlarmMoved(from = nextInA, to = nextInB)
    }

    /** Next instant at local [wall] time in [zone], strictly after now. */
    private fun nextAt(wall: LocalTime, zone: ZoneId): Instant {
        val now = ZonedDateTime.now(zone)
        val today = now.with(wall)
        return (if (today.isAfter(now)) today else today.plusDays(1)).toInstant()
    }

    private fun awaitAlarmMoved(from: Instant, to: Instant) {
        val armed = Waits.within(15_000) {
            Device.appAlarmTimes().let { to.toEpochMilli() in it && from.toEpochMilli() !in it }
        }
        assertWithMessage("alarm moved from $from to $to; app alarms: ${Device.appAlarmTimes().map(Instant::ofEpochMilli)}")
            .that(armed).isTrue()
    }

    private companion object {
        const val ZONE_A = "Asia/Kolkata"
        const val ZONE_B = "America/Los_Angeles"
    }
}
