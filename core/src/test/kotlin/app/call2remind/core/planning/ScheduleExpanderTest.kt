package app.call2remind.core.planning

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.reminder
import app.call2remind.core.Fixtures.time
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.core.time.DefaultTimes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.MonthDay

class ScheduleExpanderTest {

    private val expander = ScheduleExpander()

    private fun ist(localDateTime: String) = at(localDateTime, 5, 30)

    @Test
    fun atAppliesLeadAndWindow() {
        val r = reminder(
            schedule = Schedule.At(ist("2026-10-01T10:00"), LeadOffset.minutes(10)),
            sourceType = SourceType.CALENDAR,
        )

        assertThat(expander.fireTimes(r, ist("2026-10-01T00:00"), ist("2026-10-02T00:00")))
            .containsExactly(ist("2026-10-01T09:50"))
        // The event is inside the window but the ring (09:50) is before it.
        assertThat(expander.fireTimes(r, ist("2026-10-01T09:55"), ist("2026-10-02T00:00"))).isEmpty()
    }

    @Test
    fun dateOnlyUsesPerSourceDefaultTime() {
        val r = reminder(schedule = Schedule.DateOnly(date("2026-10-02")), sourceType = SourceType.GOOGLE_TASKS)
        val custom = ScheduleExpander(DefaultTimes.DEFAULT.with(SourceType.GOOGLE_TASKS, time("08:00")))

        assertThat(expander.fireTimes(r, ist("2026-10-01T00:00"), ist("2026-10-03T00:00")))
            .containsExactly(ist("2026-10-02T09:00"))
        assertThat(custom.fireTimes(r, ist("2026-10-01T00:00"), ist("2026-10-03T00:00")))
            .containsExactly(ist("2026-10-02T08:00"))
    }

    @Test
    fun dateOnlyExplicitTimeBeatsDefault() {
        val r = reminder(schedule = Schedule.DateOnly(date("2026-10-02"), time = time("18:30")))

        assertThat(expander.fireTimes(r, ist("2026-10-01T00:00"), ist("2026-10-03T00:00")))
            .containsExactly(ist("2026-10-02T18:30"))
    }

    @Test
    fun allDayCalendarEventOnDstDayResolvesInReminderZone() {
        val r = reminder(
            schedule = Schedule.DateOnly(date("2026-03-08")),
            sourceType = SourceType.CALENDAR,
            zone = NEW_YORK,
        )

        assertThat(expander.fireTimes(r, at("2026-03-07T00:00", -5), at("2026-03-10T00:00", -4)))
            .containsExactly(at("2026-03-08T09:00", -4))
    }

    @Test
    fun annualFeb29FallsBackToFeb28InNonLeapYears() {
        val r = reminder(schedule = Schedule.Annual(MonthDay.of(2, 29)), sourceType = SourceType.BIRTHDAY)

        val result = expander.fireTimes(r, ist("2026-01-01T00:00"), ist("2029-01-01T00:00"))

        assertThat(result).containsExactly(
            ist("2026-02-28T09:00"),
            ist("2027-02-28T09:00"),
            ist("2028-02-29T09:00"),
        ).inOrder()
    }

    @Test
    fun annualDayBeforeAcrossYearBoundary() {
        val r = reminder(
            schedule = Schedule.Annual(MonthDay.of(1, 1), lead = LeadOffset.days(1)),
            sourceType = SourceType.BIRTHDAY,
        )

        assertThat(expander.fireTimes(r, ist("2026-12-30T00:00"), ist("2027-01-02T00:00")))
            .containsExactly(ist("2026-12-31T09:00"))
    }

    @Test
    fun annualDayBeforeAcrossDstKeepsWallClockTime() {
        val r = reminder(
            schedule = Schedule.Annual(MonthDay.of(3, 9), lead = LeadOffset.days(1)),
            sourceType = SourceType.BIRTHDAY,
            zone = NEW_YORK,
        )

        assertThat(expander.fireTimes(r, at("2026-03-07T00:00", -5), at("2026-03-10T00:00", -4)))
            .containsExactly(at("2026-03-08T09:00", -4))
    }

    @Test
    fun annualRespectsSinceYear() {
        val r = reminder(schedule = Schedule.Annual(MonthDay.of(6, 1), sinceYear = 2027), sourceType = SourceType.BIRTHDAY)

        assertThat(expander.fireTimes(r, ist("2026-01-01T00:00"), ist("2027-01-01T00:00"))).isEmpty()
        assertThat(expander.fireTimes(r, ist("2026-01-01T00:00"), ist("2028-01-01T00:00")))
            .containsExactly(ist("2027-06-01T09:00"))
    }

    @Test
    fun recurringLeadCanPullARingFromBeyondTheWindowEndIntoIt() {
        val rule = RecurrenceRule(DayOfWeek.entries.toSet(), setOf(time("08:00")), date("2026-10-01"))
        val r = reminder(schedule = Schedule.Recurring(rule, LeadOffset.minutes(5)), sourceType = SourceType.HABIT)

        // Base 08:00 is after the window end (07:58) but the ring at 07:55 is inside.
        assertThat(expander.fireTimes(r, ist("2026-10-02T00:00"), ist("2026-10-02T07:58")))
            .containsExactly(ist("2026-10-02T07:55"))
    }

    @Test
    fun firesAtMatchesExactInstantsOnly() {
        val r = reminder(schedule = Schedule.DateOnly(date("2026-10-02")))

        assertThat(expander.firesAt(r, ist("2026-10-02T09:00"))).isTrue()
        assertThat(expander.firesAt(r, ist("2026-10-02T09:00").plusMillis(1))).isFalse()
        assertThat(expander.firesAt(r, ist("2026-10-02T08:59:59.999"))).isFalse()
    }

    @Test
    fun invertedWindowIsEmpty() {
        val r = reminder(schedule = Schedule.At(ist("2026-10-01T10:00")))

        assertThat(expander.fireTimes(r, ist("2026-10-02T00:00"), ist("2026-10-01T00:00"))).isEmpty()
    }
}
