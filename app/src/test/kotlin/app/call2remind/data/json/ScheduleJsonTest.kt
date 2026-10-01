package app.call2remind.data.json

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.recurrence.RecurrenceEnd
import app.call2remind.core.recurrence.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

class ScheduleJsonTest {

    private fun assertRoundTrips(schedule: Schedule) {
        val json = ScheduleJson.encode(schedule)
        assertThat(ScheduleJson.decode(json)).isEqualTo(schedule)
        // Encoding is deterministic.
        assertThat(ScheduleJson.encode(ScheduleJson.decode(json))).isEqualTo(json)
    }

    private val rule = RecurrenceRule(
        daysOfWeek = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        times = setOf(LocalTime.of(21, 30), LocalTime.of(7, 5, 30)),
        startDate = LocalDate.of(2026, 3, 2),
        intervalWeeks = 2,
    )

    @Test
    fun atRoundTripsWithNanosecondInstantAndCombinedLead() {
        assertRoundTrips(
            Schedule.At(
                instant = Instant.parse("2026-10-02T07:15:30.123456789Z"),
                lead = LeadOffset(days = 1, duration = Duration.ofMinutes(10)),
            ),
        )
    }

    @Test
    fun atRoundTripsWithoutLead() {
        assertRoundTrips(Schedule.At(Instant.parse("2026-01-01T00:00:00Z")))
    }

    @Test
    fun dateonlyRoundTripsWithAndWithoutTime() {
        assertRoundTrips(Schedule.DateOnly(LocalDate.of(2026, 10, 2)))
        assertRoundTrips(Schedule.DateOnly(LocalDate.of(2026, 10, 2), LocalTime.of(18, 45), LeadOffset.days(2)))
    }

    @Test
    fun recurringRoundTripsWithEveryEndKind() {
        assertRoundTrips(Schedule.Recurring(rule))
        assertRoundTrips(Schedule.Recurring(rule.copy(end = RecurrenceEnd.Until(LocalDate.of(2026, 12, 31)))))
        assertRoundTrips(Schedule.Recurring(rule.copy(end = RecurrenceEnd.Count(7)), LeadOffset.minutes(15)))
    }

    @Test
    fun annualRoundTripsIncludingLeapDayBirthYearAndTime() {
        assertRoundTrips(Schedule.Annual(MonthDay.of(2, 29)))
        assertRoundTrips(
            Schedule.Annual(MonthDay.of(5, 17), sinceYear = 1990, time = LocalTime.of(8, 0), lead = LeadOffset.days(1)),
        )
    }

    @Test
    fun encodingUsesATypeDiscriminatorPerVariant() {
        assertThat(ScheduleJson.encode(Schedule.At(Instant.EPOCH))).contains("\"type\":\"at\"")
        assertThat(ScheduleJson.encode(Schedule.DateOnly(LocalDate.of(2026, 1, 1)))).contains("\"type\":\"date\"")
        assertThat(ScheduleJson.encode(Schedule.Recurring(rule))).contains("\"type\":\"recurring\"")
        assertThat(ScheduleJson.encode(Schedule.Annual(MonthDay.of(1, 1)))).contains("\"type\":\"annual\"")
    }

    @Test
    fun setOrderDoesNotChangeTheEncoding() {
        val reordered = rule.copy(
            daysOfWeek = linkedSetOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            times = linkedSetOf(LocalTime.of(7, 5, 30), LocalTime.of(21, 30)),
        )
        assertThat(ScheduleJson.encode(Schedule.Recurring(reordered))).isEqualTo(ScheduleJson.encode(Schedule.Recurring(rule)))
    }

    @Test
    fun decodeAppliesDefaultsAndIgnoresUnknownKeys() {
        val decoded = ScheduleJson.decode("""{"type":"at","instant":"2026-01-01T00:00:00Z","future":42}""")
        assertThat(decoded).isEqualTo(Schedule.At(Instant.parse("2026-01-01T00:00:00Z"), LeadOffset.NONE))

        val recurring = ScheduleJson.decode(
            """{"type":"recurring","rule":{"daysOfWeek":["MONDAY"],"times":["09:00"],"startDate":"2026-03-02"}}""",
        )
        assertThat(recurring).isEqualTo(
            Schedule.Recurring(RecurrenceRule(setOf(DayOfWeek.MONDAY), setOf(LocalTime.of(9, 0)), LocalDate.of(2026, 3, 2))),
        )
    }

    @Test
    fun decodeRejectsUnknownTypesAndMalformedValues() {
        assertThrows(SerializationException::class.java) {
            ScheduleJson.decode("""{"type":"weekly","instant":"2026-01-01T00:00:00Z"}""")
        }
        assertThrows(SerializationException::class.java) { ScheduleJson.decode("not json") }
        assertThrows(DateTimeException::class.java) {
            ScheduleJson.decode("""{"type":"date","date":"2026-02-30"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleJson.decode("""{"type":"at","instant":"2026-01-01T00:00:00Z","lead":{"days":-1}}""")
        }
    }
}
