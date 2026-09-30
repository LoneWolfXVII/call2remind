package app.call2remind.data.mapper

import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceEnd
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.db.OccurrenceWithReminderRow
import app.call2remind.data.model.ReminderSource
import app.call2remind.testing.T0
import app.call2remind.testing.hours
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

class MappersTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private val schedules: List<Schedule> = listOf(
        Schedule.At(T0.plus(hours(3)), LeadOffset.minutes(10)),
        Schedule.DateOnly(LocalDate.of(2026, 3, 11)),
        Schedule.DateOnly(LocalDate.of(2026, 3, 11), LocalTime.of(7, 30), LeadOffset.days(1)),
        Schedule.Recurring(
            RecurrenceRule(
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                times = setOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                startDate = LocalDate.of(2026, 3, 1),
                intervalWeeks = 2,
                end = RecurrenceEnd.Count(10),
            ),
        ),
        Schedule.Annual(MonthDay.of(2, 29), sinceYear = 1992, time = LocalTime.of(9, 15), lead = LeadOffset.days(1)),
    )

    @Test
    fun reminderRoundTripsForEveryScheduleVariant() {
        for ((index, schedule) in schedules.withIndex()) {
            val original = reminder(
                externalId = "x$index",
                schedule = schedule,
                sourceType = SourceType.entries[index % SourceType.entries.size],
                notes = "notes $index",
                zone = berlin,
                enabled = index % 2 == 0,
                ringtoneUri = "content://tones/$index",
                ttsEnabled = index % 2 == 1,
            )
            val entity = original.toEntity(sourceId = "src", updatedAt = T0)

            assertThat(entity.sourceId).isEqualTo("src")
            assertThat(entity.updatedAt).isEqualTo(T0)
            assertThat(entity.zoneId).isEqualTo("Europe/Berlin")
            assertThat(entity.toModel()).isEqualTo(original)
            assertThat(entity.toModelOrNull()).isEqualTo(original)
        }
    }

    @Test
    fun unreadableReminderRowsMapToNull() {
        val good = reminder().toEntity(null, T0)
        assertThat(good.copy(scheduleJson = "{broken").toModelOrNull()).isNull()
        assertThat(good.copy(zoneId = "Mars/Olympus").toModelOrNull()).isNull()
    }

    @Test
    fun occurrenceRoundTripsAllFields() {
        val r = reminder()
        val original = occurrence(
            r,
            plannedAt = T0,
            state = OccurrenceState.RINGING,
            fireAt = T0.plusSeconds(90),
            ringBacks = 2,
            answeredAt = T0.plusSeconds(100),
        )
        val entity = original.toEntity()

        assertThat(entity.requestCode).isEqualTo(original.requestCode)
        assertThat(entity.toModel()).isEqualTo(original)
        assertThat(occurrence(r, T0).toEntity().toModel().answeredAt).isNull()
    }

    @Test
    fun ringLogAndSourceRoundTrip() {
        val event = RingLogEvent("occ", RingLogType.SNOOZED, T0, RingLogEvent.REASON_DECLINED)
        assertThat(event.toEntity().toModel()).isEqualTo(event)
        assertThat(RingLogEvent("occ", RingLogType.FIRED, T0).toEntity().toModel().reason).isNull()

        val source = ReminderSource(
            id = "cal:1",
            type = SourceType.CALENDAR,
            account = "me@example.com",
            displayName = "Work",
            enabled = false,
            lastSyncAt = T0,
            syncCursor = "cursor",
        )
        assertThat(source.toEntity().toModel()).isEqualTo(source)
        assertThat(ReminderSource("h", SourceType.HABIT).toEntity().toModel()).isEqualTo(ReminderSource("h", SourceType.HABIT))
    }

    @Test
    fun occurrenceWithReminderKeepsRowsWhoseReminderIsGoneOrCorrupt() {
        val r = reminder()
        val occ = occurrence(r, T0)

        val joined = OccurrenceWithReminderRow(occ.toEntity(), r.toEntity(null, T0)).toModel()
        assertThat(joined.occurrence).isEqualTo(occ)
        assertThat(joined.reminder).isEqualTo(r)

        assertThat(OccurrenceWithReminderRow(occ.toEntity(), null).toModel().reminder).isNull()
        val corrupt = r.toEntity(null, T0).copy(scheduleJson = "nope")
        assertThat(OccurrenceWithReminderRow(occ.toEntity(), corrupt).toModel().reminder).isNull()
    }
}
