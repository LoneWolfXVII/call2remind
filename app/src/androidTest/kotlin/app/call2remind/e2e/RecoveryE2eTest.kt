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
import java.time.LocalDate
import java.time.ZoneId

/**
 * Losing alarms and moving clocks: BOOT_COMPLETED re-arms alarms the OS dropped, and a time zone
 * change keeps date-only reminders at their wall-clock time in the new zone.
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
        val moved = Waits.within(60_000) { AppDriver.activeFor(reminder.id).map { it.fireAt } == listOf(atB) }
        assertWithMessage(
            "after the zone change the occurrence rings at $date $time in $ZONE_B ($atB), not $atA; " +
                "active: ${AppDriver.activeFor(reminder.id).map { it.fireAt }}, reminder zone now ${AppDriver.reminderByTitle(title)?.zoneId}",
        ).that(moved).isTrue()
        assertWithMessage("re-armed for the new wall time").that(Device.pendingAppAlarmCount()).isAtLeast(1)
    }

    private companion object {
        const val ZONE_A = "Asia/Kolkata"
        const val ZONE_B = "America/Los_Angeles"
    }
}
