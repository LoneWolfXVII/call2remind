package app.call2remind.ui.history

import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.hours
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HistoryGroupingTest {
    private fun row(id: String, at: Instant, state: OccurrenceState, ringBacks: Int = 0): OccurrenceWithReminder {
        val r = reminder(id, Schedule.At(at), title = id)
        return OccurrenceWithReminder(occurrence(r, at, state = state, ringBacks = ringBacks), r)
    }

    private val history = listOf(
        row("missedToday", T0.minus(hours(1)), OccurrenceState.MISSED, ringBacks = 2),
        row("missedYesterday", T0.minus(hours(20)), OccurrenceState.MISSED),
        row("doneToday", T0.minus(hours(2)), OccurrenceState.DONE),
        row("skippedToday", T0.minus(hours(3)), OccurrenceState.SKIPPED),
        row("doneYesterday", T0.minus(hours(22)), OccurrenceState.DONE, ringBacks = 1),
    )

    @Test
    fun missedFirstThenFinishedByDayNewestFirst() {
        val model = HistoryGrouping.build(history, HistoryFilter.ALL, UTC)

        assertThat(model.missed.map { it.title }).containsExactly("missedToday", "missedYesterday").inOrder()
        assertThat(model.missed.first().ringBacks).isEqualTo(2)
        assertThat(model.days.map { it.date }).containsExactly(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 9)).inOrder()
        assertThat(model.days[0].items.map { it.title }).containsExactly("doneToday", "skippedToday").inOrder()
        assertThat(model.days[1].items.map { it.title }).containsExactly("doneYesterday")
    }

    @Test
    fun missedOnlyFilter() {
        val model = HistoryGrouping.build(history, HistoryFilter.MISSED, UTC)

        assertThat(model.missed).hasSize(2)
        assertThat(model.days).isEmpty()
    }

    @Test
    fun rungAgainMissedCallsLeaveTheMissedList() {
        val id = history.first().occurrence.id

        val model = HistoryGrouping.build(history, HistoryFilter.ALL, UTC, ringing = setOf(id))

        assertThat(model.missed.map { it.title }).containsExactly("missedYesterday")
    }

    @Test
    fun emptyHistory() {
        assertThat(HistoryGrouping.build(emptyList(), HistoryFilter.ALL, UTC).isEmpty).isTrue()
    }
}
