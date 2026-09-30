package app.call2remind.core.ringing

import app.call2remind.core.Fixtures.instant
import app.call2remind.core.Fixtures.occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingQueueTest {

    private val now = instant("2026-10-01T12:00:00Z")

    @Test
    fun ordersByFireTimeThenSourcePriorityThenId() {
        val birthday = occurrence(id = "a", fireAt = now, sourceType = SourceType.BIRTHDAY)
        val calendarB = occurrence(id = "b", fireAt = now, sourceType = SourceType.CALENDAR)
        val calendarA = occurrence(id = "a2", fireAt = now, sourceType = SourceType.CALENDAR)
        val habitEarlier = occurrence(id = "z", fireAt = now.minusSeconds(1), sourceType = SourceType.HABIT)
        val task = occurrence(id = "t", fireAt = now, sourceType = SourceType.GOOGLE_TASKS)

        val due = RingQueue.due(listOf(birthday, task, calendarB, habitEarlier, calendarA), now)

        assertThat(due.map { it.id }).containsExactly("z", "a2", "b", "t", "a").inOrder()
    }

    @Test
    fun orderIsIndependentOfInputOrder() {
        val items = (0 until 10).map {
            occurrence(id = "o$it", fireAt = now.minusSeconds((it % 3).toLong()), sourceType = SourceType.entries[it % 6])
        }

        assertThat(RingQueue.due(items.reversed(), now)).containsExactlyElementsIn(RingQueue.due(items, now)).inOrder()
    }

    @Test
    fun onlyPendingAndDueOccurrencesQualify() {
        val due = occurrence(id = "due", fireAt = now)
        val snoozed = occurrence(id = "snoozed", fireAt = now.minusSeconds(5), state = OccurrenceState.SNOOZED)
        val future = occurrence(id = "future", fireAt = now.plusMillis(1))
        val done = occurrence(id = "done", fireAt = now.minusSeconds(5), state = OccurrenceState.DONE)
        val missed = occurrence(id = "missed", fireAt = now.minusSeconds(5), state = OccurrenceState.MISSED)

        assertThat(RingQueue.due(listOf(due, snoozed, future, done, missed), now).map { it.id })
            .containsExactly("snoozed", "due").inOrder()
    }

    @Test
    fun nextIsNullWhileSomethingIsRinging() {
        val ringing = occurrence(id = "r", fireAt = now.minusSeconds(10), state = OccurrenceState.RINGING)
        val waiting = occurrence(id = "w", fireAt = now.minusSeconds(20))

        assertThat(RingQueue.isLineBusy(listOf(ringing, waiting))).isTrue()
        assertThat(RingQueue.next(listOf(ringing, waiting), now)).isNull()
        assertThat(RingQueue.next(listOf(waiting), now)).isEqualTo(waiting)
    }

    @Test
    fun nextIsNullWhenNothingDue() {
        assertThat(RingQueue.next(listOf(occurrence(fireAt = now.plusSeconds(60))), now)).isNull()
        assertThat(RingQueue.next(emptyList(), now)).isNull()
    }
}
