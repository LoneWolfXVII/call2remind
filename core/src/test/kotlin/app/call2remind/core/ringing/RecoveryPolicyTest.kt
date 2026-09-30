package app.call2remind.core.ringing

import app.call2remind.core.Fixtures.instant
import app.call2remind.core.Fixtures.occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class RecoveryPolicyTest {

    private val now = instant("2026-10-01T12:00:00Z")
    private val policy = RecoveryPolicy()

    @Test
    fun overdueByLessThanTwoHoursRingsNow() {
        val late = occurrence(fireAt = now.minus(Duration.ofMinutes(119)))

        assertThat(policy.actionFor(late, now)).isEqualTo(RecoveryAction.RingNow(late, Duration.ofMinutes(119)))
    }

    @Test
    fun overdueByTwoHoursOrMoreIsMissed() {
        val exactly = occurrence(id = "a", fireAt = now.minus(Duration.ofHours(2)))
        val older = occurrence(id = "b", fireAt = now.minus(Duration.ofDays(1)))

        assertThat(policy.actionFor(exactly, now)).isEqualTo(RecoveryAction.MarkMissed(exactly, Duration.ofHours(2)))
        assertThat(policy.actionFor(older, now)).isEqualTo(RecoveryAction.MarkMissed(older, Duration.ofDays(1)))
    }

    @Test
    fun snoozedUsesItsSnoozedFireTime() {
        val snoozed = occurrence(fireAt = now.minus(Duration.ofHours(5)), state = OccurrenceState.SNOOZED)
            .copy(fireAt = now.minusSeconds(30))

        assertThat(policy.actionFor(snoozed, now)).isEqualTo(RecoveryAction.RingNow(snoozed, Duration.ofSeconds(30)))
    }

    @Test
    fun dueNowOrInTheFutureNeedsNothing() {
        assertThat(policy.actionFor(occurrence(fireAt = now), now)).isNull()
        assertThat(policy.actionFor(occurrence(fireAt = now.plusSeconds(1)), now)).isNull()
    }

    @Test
    fun stuckUnansweredRingingTimesOutAfterTimeoutPlusGrace() {
        val justUnder = occurrence(fireAt = now.minusSeconds(74), state = OccurrenceState.RINGING)
        val atLimit = occurrence(fireAt = now.minusSeconds(75), state = OccurrenceState.RINGING)

        assertThat(policy.actionFor(justUnder, now)).isNull()
        assertThat(policy.actionFor(atLimit, now)).isEqualTo(RecoveryAction.TimeOutRinging(atLimit))
    }

    @Test
    fun staleAnsweredCallIsFinished() {
        val fresh = occurrence(fireAt = now.minus(Duration.ofMinutes(20)), state = OccurrenceState.RINGING)
            .copy(answeredAt = now.minus(Duration.ofMinutes(14)))
        val stale = fresh.copy(answeredAt = now.minus(Duration.ofMinutes(15)))

        assertThat(policy.actionFor(fresh, now)).isNull()
        assertThat(policy.actionFor(stale, now)).isEqualTo(RecoveryAction.FinishAnswered(stale))
    }

    @Test
    fun terminalStatesAreIgnored() {
        listOf(OccurrenceState.DONE, OccurrenceState.MISSED, OccurrenceState.SKIPPED).forEach { state ->
            assertThat(policy.actionFor(occurrence(fireAt = now.minus(Duration.ofDays(3)), state = state), now)).isNull()
        }
    }

    @Test
    fun recoverReturnsActionsInRingOrder() {
        val birthday = occurrence(id = "b", fireAt = now.minusSeconds(600), sourceType = SourceType.BIRTHDAY)
        val calendar = occurrence(id = "c", fireAt = now.minusSeconds(600), sourceType = SourceType.CALENDAR)
        val earliest = occurrence(id = "z", fireAt = now.minusSeconds(900), sourceType = SourceType.HABIT)
        val future = occurrence(id = "f", fireAt = now.plusSeconds(900))
        val ancient = occurrence(id = "a", fireAt = now.minus(Duration.ofHours(3)))

        val actions = policy.recover(listOf(birthday, future, calendar, ancient, earliest), now)

        assertThat(actions.map { it.occurrence.id }).containsExactly("a", "z", "c", "b").inOrder()
        assertThat(actions.first()).isInstanceOf(RecoveryAction.MarkMissed::class.java)
    }

    @Test
    fun customThreshold() {
        val strict = RecoveryPolicy(ringIfLateWithin = Duration.ofMinutes(10))
        val late = occurrence(fireAt = now.minus(Duration.ofMinutes(11)))

        assertThat(strict.actionFor(late, now)).isInstanceOf(RecoveryAction.MarkMissed::class.java)
    }
}
