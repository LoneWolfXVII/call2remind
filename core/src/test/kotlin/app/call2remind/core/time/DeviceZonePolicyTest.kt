package app.call2remind.core.time

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.instant
import app.call2remind.core.Fixtures.reminder
import app.call2remind.core.Fixtures.time
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.planning.ScheduleExpander
import app.call2remind.core.recurrence.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

class DeviceZonePolicyTest {
    private val la: ZoneId = ZoneId.of("America/Los_Angeles")
    private val dateOnly = Schedule.DateOnly(date("2026-10-02"), time("09:00"))
    private val daily9 = Schedule.Recurring(RecurrenceRule(DayOfWeek.values().toSet(), setOf(time("09:00")), date("2026-01-01")))

    @Test
    fun syncedDateSourcesAlwaysFollowTheDeviceZone() {
        for (type in listOf(SourceType.CALENDAR, SourceType.GOOGLE_TASKS, SourceType.BIRTHDAY)) {
            assertThat(DeviceZonePolicy.followsDeviceZone(reminder(schedule = dateOnly, sourceType = type), habitsFollow = false)).isTrue()
            assertThat(DeviceZonePolicy.followsDeviceZone(reminder(schedule = dateOnly, sourceType = type), habitsFollow = true)).isTrue()
        }
    }

    @Test
    fun explicitZoneAndInstantSourcesNeverFollow() {
        for (type in listOf(SourceType.MS_TODO, SourceType.SAMSUNG_REMINDER)) {
            assertThat(DeviceZonePolicy.followsDeviceZone(reminder(schedule = dateOnly, sourceType = type))).isFalse()
        }
    }

    @Test
    fun habitsFollowPerTheSwitch() {
        val habit = reminder(schedule = daily9, sourceType = SourceType.HABIT)

        assertThat(DeviceZonePolicy.followsDeviceZone(habit, habitsFollow = true)).isTrue()
        assertThat(DeviceZonePolicy.followsDeviceZone(habit, habitsFollow = false)).isFalse()
        assertThat(DeviceZonePolicy.followsDeviceZone(habit)).isEqualTo(DeviceZonePolicy.HABITS_FOLLOW_DEVICE_ZONE)
    }

    @Test
    fun toDeviceZoneMovesOnlyFollowersInAnotherZone() {
        val calendar = reminder("c", dateOnly, SourceType.CALENDAR, zone = KOLKATA)
        val alreadyThere = reminder("t", dateOnly, SourceType.GOOGLE_TASKS, zone = la)
        val todo = reminder("m", dateOnly, SourceType.MS_TODO, zone = NEW_YORK)
        val habit = reminder("h", daily9, SourceType.HABIT, zone = KOLKATA)

        val moved = DeviceZonePolicy.toDeviceZone(listOf(calendar, alreadyThere, todo, habit), la, habitsFollow = true)

        assertThat(moved).containsExactly(calendar.copy(zone = la), habit.copy(zone = la)).inOrder()
        assertThat(DeviceZonePolicy.toDeviceZone(listOf(calendar, habit), la, habitsFollow = false))
            .containsExactly(calendar.copy(zone = la))
        assertThat(DeviceZonePolicy.toDeviceZone(listOf(alreadyThere, todo), la)).isEmpty()
    }

    @Test
    fun aMovedReminderRingsAtTheSameWallTimeInTheNewZone() {
        val habit = reminder("h", daily9, SourceType.HABIT, zone = KOLKATA)
        val expander = ScheduleExpander()
        val from = instant("2026-10-01T20:00:00Z")
        val to = from.plusSeconds(86_400)

        val before = expander.fireTimes(habit, from, to)
        val after = expander.fireTimes(DeviceZonePolicy.toDeviceZone(listOf(habit), la, habitsFollow = true).single(), from, to)

        // 09:00 IST on Oct 2 = 03:30Z; 09:00 PDT on Oct 2 = 16:00Z.
        assertThat(before).containsExactly(Instant.parse("2026-10-02T03:30:00Z"))
        assertThat(after).containsExactly(Instant.parse("2026-10-02T16:00:00Z"))
    }

    @Test
    fun deviceClockReadsTheZoneOnEveryCall() {
        var zone: ZoneId = KOLKATA
        val clock = DeviceClock { zone }

        assertThat(clock.zone).isEqualTo(KOLKATA)
        zone = la
        assertThat(clock.zone).isEqualTo(la)
        assertThat(clock.withZone(NEW_YORK).zone).isEqualTo(NEW_YORK)
        val before = System.currentTimeMillis()
        assertThat(clock.millis()).isAtLeast(before)
        assertThat(clock.instant().toEpochMilli()).isAtLeast(before)
    }

    @Test
    fun defaultDeviceClockUsesTheCurrentDefaultZone() {
        val original = java.util.TimeZone.getDefault()
        try {
            val clock = DeviceClock()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            assertThat(clock.zone).isEqualTo(KOLKATA)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertThat(clock.zone).isEqualTo(la)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
