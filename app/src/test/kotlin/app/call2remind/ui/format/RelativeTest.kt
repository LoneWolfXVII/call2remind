package app.call2remind.ui.format

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class RelativeTest {
    @Test
    fun agoBuckets() {
        assertThat(Relative.ago(T0, T0.plusSeconds(20))).isEqualTo(Ago.JustNow)
        assertThat(Relative.ago(T0, T0.plus(minutes(3)))).isEqualTo(Ago.Minutes(3))
        assertThat(Relative.ago(T0, T0.plus(hours(5)))).isEqualTo(Ago.Hours(5))
        assertThat(Relative.ago(T0, T0.plus(hours(50)))).isEqualTo(Ago.Days(2))
        // A clock that moved backwards never shows a negative span.
        assertThat(Relative.ago(T0, T0.minus(minutes(5)))).isEqualTo(Ago.JustNow)
    }

    @Test
    fun untilRoundsMinutesUpAndNamesLaterDays() {
        // T0 = 08:00 UTC.
        assertThat(Relative.until(T0.plusSeconds(30), T0, UTC)).isEqualTo(Until.Now)
        assertThat(Relative.until(T0.plus(minutes(20)).plusSeconds(10), T0, UTC)).isEqualTo(Until.Minutes(21))
        assertThat(Relative.until(T0.plus(hours(2)).plus(minutes(15)), T0, UTC)).isEqualTo(Until.HoursMinutes(2, 15))
        assertThat(Relative.until(T0.plus(hours(25)), T0, UTC)).isEqualTo(Until.Tomorrow)
        assertThat(Relative.until(T0.plus(hours(72)), T0, UTC)).isEqualTo(Until.OnDay(LocalDate.of(2026, 3, 13)))
    }

    @Test
    fun justAfterMidnightTomorrowStillCountsMinutes() {
        val lateEvening = T0.plus(hours(15)).plus(minutes(50)) // 23:50
        assertThat(Relative.until(lateEvening.plus(minutes(20)), lateEvening, UTC)).isEqualTo(Until.Minutes(20))
    }

    @Test
    fun daySummaries() {
        val locale = Locale.UK
        assertThat(ScheduleText.days(DayOfWeek.entries.toSet(), locale)).isEqualTo(DaysSummary.Daily)
        assertThat(ScheduleText.days(DayOfWeek.entries.take(5).toSet(), locale)).isEqualTo(DaysSummary.Weekdays)
        assertThat(ScheduleText.days(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), locale)).isEqualTo(DaysSummary.Weekends)
        assertThat(ScheduleText.days(setOf(DayOfWeek.SATURDAY, DayOfWeek.TUESDAY), locale))
            .isEqualTo(DaysSummary.Specific(listOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)))
    }

    @Test
    fun weekOrderFollowsTheLocale() {
        assertThat(localeWeek(Locale.UK).first()).isEqualTo(DayOfWeek.MONDAY)
        assertThat(localeWeek(Locale.US).first()).isEqualTo(DayOfWeek.SUNDAY)
    }

    @Test
    fun leads() {
        assertThat(ScheduleText.lead(LeadOffset.NONE)).isEqualTo(ScheduleText.Lead.AtTime)
        assertThat(ScheduleText.lead(LeadOffset.minutes(10))).isEqualTo(ScheduleText.Lead.MinutesBefore(10))
        assertThat(ScheduleText.lead(LeadOffset.days(1))).isEqualTo(ScheduleText.Lead.DaysBefore(1))
        assertThat(ScheduleText.usesDefaultTime(Schedule.DateOnly(LocalDate.of(2026, 3, 11)))).isTrue()
        assertThat(ScheduleText.usesDefaultTime(Schedule.At(T0))).isFalse()
    }
}
