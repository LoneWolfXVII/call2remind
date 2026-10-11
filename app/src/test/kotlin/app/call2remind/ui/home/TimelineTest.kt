package app.call2remind.ui.home

import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class TimelineTest {
    // T0 = Tuesday 2026-03-10 08:00 UTC.
    private fun row(id: String, at: Instant, state: OccurrenceState = OccurrenceState.SCHEDULED, source: SourceType = SourceType.CALENDAR): OccurrenceWithReminder {
        val r = reminder(id, Schedule.At(at), sourceType = source, title = "Title $id")
        return OccurrenceWithReminder(occurrence(r, at, state = state), r)
    }

    @Test
    fun groupsByLocalDayAndLightsTheNextCall() {
        val upcoming = listOf(
            row("later", T0.plus(hours(72))),
            row("tomorrow", T0.plus(hours(20))),
            row("today2", T0.plus(hours(3))),
            row("today1", T0.plus(minutes(20))),
        )

        val model = Timeline.build(upcoming, emptyList(), T0, UTC)

        assertThat(model.sections.map { it.group }).containsExactly(TimelineGroup.TODAY, TimelineGroup.TOMORROW, TimelineGroup.LATER).inOrder()
        assertThat(model.sections[0].rows.map { it.title }).containsExactly("Title today1", "Title today2").inOrder()
        assertThat(model.sections[0].date).isEqualTo(LocalDate.of(2026, 3, 10))
        assertThat(model.sections[1].date).isEqualTo(LocalDate.of(2026, 3, 11))
        assertThat(model.next?.title).isEqualTo("Title today1")
        assertThat(model.sections.flatMap { it.rows }.filter { it.isNext }.map { it.title }).containsExactly("Title today1")
        assertThat(model.isEmpty).isFalse()
    }

    @Test
    fun ringingIsSeparateAndNeverTheNextCall() {
        val upcoming = listOf(
            row("ringing", T0.minus(minutes(1)), OccurrenceState.RINGING),
            row("soon", T0.plus(minutes(30))),
        )

        val model = Timeline.build(upcoming, emptyList(), T0, UTC)

        assertThat(model.ringing.map { it.title }).containsExactly("Title ringing")
        assertThat(model.next?.title).isEqualTo("Title soon")
        assertThat(model.sections.flatMap { it.rows }.map { it.title }).doesNotContain("Title ringing")
    }

    @Test
    fun overdueAndSnoozedStayInToday() {
        val upcoming = listOf(row("overdue", T0.minus(hours(1)), OccurrenceState.SNOOZED))

        val model = Timeline.build(upcoming, emptyList(), T0, UTC)

        assertThat(model.sections.single().group).isEqualTo(TimelineGroup.TODAY)
        assertThat(model.next?.isSnoozed).isTrue()
    }

    @Test
    fun todaysFinishedCallsInterleaveButYesterdaysDont() {
        val history = listOf(
            row("doneToday", T0.minus(hours(1)), OccurrenceState.DONE),
            row("skippedToday", T0.minus(minutes(10)), OccurrenceState.SKIPPED),
            row("missedToday", T0.minus(minutes(5)), OccurrenceState.MISSED),
            row("doneYesterday", T0.minus(hours(20)), OccurrenceState.DONE),
        )
        val upcoming = listOf(row("next", T0.plus(hours(1))))

        val model = Timeline.build(upcoming, history, T0, UTC)

        val today = model.sections.single { it.group == TimelineGroup.TODAY }.rows
        assertThat(today.map { it.title }).containsExactly("Title doneToday", "Title skippedToday", "Title next").inOrder()
        assertThat(today.filter { it.isFinished }.map { it.state }).containsExactly(OccurrenceState.DONE, OccurrenceState.SKIPPED)
        assertThat(model.next?.title).isEqualTo("Title next")
    }

    @Test
    fun onlyFinishedRowsIsTheEmptyState() {
        val model = Timeline.build(emptyList(), listOf(row("done", T0.minus(hours(1)), OccurrenceState.DONE)), T0, UTC)

        assertThat(model.isEmpty).isTrue()
        assertThat(model.rowCount).isEqualTo(1)
    }

    @Test
    fun hiddenRowsAndOrphansAreLeftOut() {
        val orphan = row("orphan", T0.plus(hours(1))).copy(reminder = null)
        val upcoming = listOf(row("a", T0.plus(hours(1))), row("b", T0.plus(hours(2))), orphan)

        val model = Timeline.build(upcoming, emptyList(), T0, UTC, hidden = setOf(upcoming[0].occurrence.id))

        assertThat(model.sections.flatMap { it.rows }.map { it.title }).containsExactly("Title b")
        assertThat(model.next?.title).isEqualTo("Title b")
    }

    @Test
    fun cadenceDescribesTheSchedule() {
        val rule = RecurrenceRule(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), setOf(LocalTime.of(18, 30)), LocalDate.of(2026, 3, 1), intervalWeeks = 2)

        assertThat(Timeline.cadenceOf(Schedule.Recurring(rule))).isEqualTo(RowCadence.Habit(rule.daysOfWeek, 2))
        assertThat(Timeline.cadenceOf(Schedule.DateOnly(LocalDate.of(2026, 3, 11)))).isEqualTo(RowCadence.NoTimeSet)
        assertThat(Timeline.cadenceOf(Schedule.DateOnly(LocalDate.of(2026, 3, 11), LocalTime.NOON))).isEqualTo(RowCadence.Once)
        assertThat(Timeline.cadenceOf(Schedule.At(T0))).isEqualTo(RowCadence.Once)
    }
}
