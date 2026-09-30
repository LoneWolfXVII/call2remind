package app.call2remind.core.ringing

import app.call2remind.core.Fixtures.fixedClock
import app.call2remind.core.Fixtures.instant
import app.call2remind.core.Fixtures.occurrence
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.OccurrenceState.DONE
import app.call2remind.core.model.OccurrenceState.MISSED
import app.call2remind.core.model.OccurrenceState.RINGING
import app.call2remind.core.model.OccurrenceState.SCHEDULED
import app.call2remind.core.model.OccurrenceState.SKIPPED
import app.call2remind.core.model.OccurrenceState.SNOOZED
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration

class OccurrenceStateMachineTest {

    private val planned = instant("2026-10-01T04:30:00Z")
    private val now = instant("2026-10-01T04:31:00Z")
    private val machine = OccurrenceStateMachine(fixedClock(now))

    private fun inState(state: OccurrenceState, ringBacks: Int = 0, answered: Boolean = false): Occurrence =
        occurrence(fireAt = planned, state = state, ringBacks = ringBacks, answeredAt = if (answered) planned else null)

    private fun accepted(result: TransitionResult): TransitionResult.Transitioned {
        assertThat(result).isInstanceOf(TransitionResult.Transitioned::class.java)
        return result as TransitionResult.Transitioned
    }

    private fun assertInvalid(from: Occurrence, event: OccurrenceEvent) {
        assertThat(machine.transition(from, event)).isEqualTo(TransitionResult.InvalidTransition(from.state, event))
    }

    @Test
    fun fireFromScheduledStartsRingingNow() {
        val result = accepted(machine.transition(inState(SCHEDULED), OccurrenceEvent.Fire))

        assertThat(result.state).isEqualTo(RINGING)
        assertThat(result.occurrence.fireAt).isEqualTo(now)
        assertThat(result.occurrence.plannedAt).isEqualTo(planned)
        assertThat(result.nextFireAt).isNull()
        assertThat(result.logEvent).isEqualTo(RingLogEvent("o1", RingLogType.FIRED, now))
    }

    @Test
    fun fireFromSnoozedKeepsRingBacks() {
        val result = accepted(machine.transition(inState(SNOOZED, ringBacks = 2), OccurrenceEvent.Fire))

        assertThat(result.state).isEqualTo(RINGING)
        assertThat(result.occurrence.ringBacks).isEqualTo(2)
    }

    @Test
    fun answerMarksAnsweredButKeepsRinging() {
        val result = accepted(machine.transition(inState(RINGING), OccurrenceEvent.Answer))

        assertThat(result.state).isEqualTo(RINGING)
        assertThat(result.occurrence.answeredAt).isEqualTo(now)
        assertThat(result.logEvent.type).isEqualTo(RingLogType.ANSWERED)
    }

    @Test
    fun doneAfterAnswer() {
        val result = accepted(machine.transition(inState(RINGING, answered = true), OccurrenceEvent.Done))

        assertThat(result.state).isEqualTo(DONE)
        assertThat(result.occurrence.answeredAt).isNull()
        assertThat(result.nextFireAt).isNull()
        assertThat(result.logEvent.type).isEqualTo(RingLogType.DONE)
    }

    @Test
    fun doneIsAllowedAheadOfTimeAndFromMissed() {
        listOf(SCHEDULED, SNOOZED, MISSED).forEach { state ->
            assertThat(accepted(machine.transition(inState(state), OccurrenceEvent.Done)).state).isEqualTo(DONE)
        }
    }

    @Test
    fun snoozeWithCustomDuration() {
        val result = accepted(
            machine.transition(inState(RINGING, answered = true), OccurrenceEvent.Snooze(Duration.ofMinutes(15))),
        )

        assertThat(result.state).isEqualTo(SNOOZED)
        assertThat(result.nextFireAt).isEqualTo(now.plus(Duration.ofMinutes(15)))
        assertThat(result.occurrence.fireAt).isEqualTo(now.plus(Duration.ofMinutes(15)))
        assertThat(result.occurrence.ringBacks).isEqualTo(1)
        assertThat(result.occurrence.answeredAt).isNull()
        assertThat(result.logEvent)
            .isEqualTo(RingLogEvent("o1", RingLogType.SNOOZED, now, RingLogEvent.REASON_USER_SNOOZE))
    }

    @Test
    fun declineSnoozesForDefaultFiveMinutes() {
        val result = accepted(machine.transition(inState(RINGING), OccurrenceEvent.Decline))

        assertThat(result.state).isEqualTo(SNOOZED)
        assertThat(result.nextFireAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
        assertThat(result.logEvent.reason).isEqualTo(RingLogEvent.REASON_DECLINED)
    }

    @Test
    fun ringTimeoutAutoSnoozes() {
        val result = accepted(machine.transition(inState(RINGING), OccurrenceEvent.RingTimeout))

        assertThat(result.state).isEqualTo(SNOOZED)
        assertThat(result.nextFireAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
        assertThat(result.logEvent.reason).isEqualTo(RingLogEvent.REASON_RING_TIMEOUT)
    }

    @Test
    fun ringTimeoutAfterAnswerIsInvalid() {
        assertInvalid(inState(RINGING, answered = true), OccurrenceEvent.RingTimeout)
    }

    @Test
    fun answeringTwiceIsInvalid() {
        assertInvalid(inState(RINGING, answered = true), OccurrenceEvent.Answer)
    }

    @Test
    fun threeRingBacksThenMissed() {
        var occurrence = inState(SCHEDULED)
        val rings = mutableListOf<OccurrenceState>()
        repeat(4) {
            occurrence = accepted(machine.transition(occurrence, OccurrenceEvent.Fire)).occurrence
            val result = accepted(machine.transition(occurrence, OccurrenceEvent.Decline))
            occurrence = result.occurrence
            rings += occurrence.state
        }

        assertThat(rings).containsExactly(SNOOZED, SNOOZED, SNOOZED, MISSED).inOrder()
        assertThat(occurrence.ringBacks).isEqualTo(3)
    }

    @Test
    fun snoozeBeyondLimitIsMissedWithReason() {
        listOf(OccurrenceEvent.Decline, OccurrenceEvent.RingTimeout, OccurrenceEvent.Snooze(Duration.ofMinutes(10)))
            .forEach { event ->
                val result = accepted(machine.transition(inState(RINGING, ringBacks = 3), event))
                assertThat(result.state).isEqualTo(MISSED)
                assertThat(result.nextFireAt).isNull()
                assertThat(result.logEvent.type).isEqualTo(RingLogType.MISSED)
                assertThat(result.logEvent.reason).isEqualTo(RingLogEvent.REASON_MAX_RING_BACKS)
            }
    }

    @Test
    fun customPolicy() {
        val strict = OccurrenceStateMachine(
            fixedClock(now),
            SnoozePolicy(defaultSnooze = Duration.ofMinutes(10), maxRingBacks = 0),
        )

        assertThat(accepted(strict.transition(inState(RINGING), OccurrenceEvent.Decline)).state).isEqualTo(MISSED)

        val lenient = OccurrenceStateMachine(fixedClock(now), SnoozePolicy(defaultSnooze = Duration.ofMinutes(10)))
        assertThat(accepted(lenient.transition(inState(RINGING), OccurrenceEvent.Decline)).nextFireAt)
            .isEqualTo(now.plus(Duration.ofMinutes(10)))
    }

    @Test
    fun skipAndMarkMissedFromActiveStates() {
        listOf(SCHEDULED, SNOOZED, RINGING).forEach { state ->
            assertThat(accepted(machine.transition(inState(state), OccurrenceEvent.Skip)).state).isEqualTo(SKIPPED)
            val missed = accepted(machine.transition(inState(state), OccurrenceEvent.MarkMissed))
            assertThat(missed.state).isEqualTo(MISSED)
            assertThat(missed.logEvent.type).isEqualTo(RingLogType.MISSED)
        }
    }

    @Test
    fun invalidTransitions() {
        val ringingOnly = listOf(
            OccurrenceEvent.Answer,
            OccurrenceEvent.Decline,
            OccurrenceEvent.RingTimeout,
            OccurrenceEvent.Snooze(Duration.ofMinutes(5)),
        )
        listOf(SCHEDULED, SNOOZED).forEach { state -> ringingOnly.forEach { assertInvalid(inState(state), it) } }

        assertInvalid(inState(RINGING), OccurrenceEvent.Fire)

        val allEvents = ringingOnly + listOf(
            OccurrenceEvent.Fire,
            OccurrenceEvent.Done,
            OccurrenceEvent.Skip,
            OccurrenceEvent.MarkMissed,
        )
        allEvents.forEach { assertInvalid(inState(DONE), it) }
        allEvents.forEach { assertInvalid(inState(SKIPPED), it) }
        allEvents.filter { it != OccurrenceEvent.Done }.forEach { assertInvalid(inState(MISSED), it) }
    }

    @Test
    fun snoozeRequiresPositiveDuration() {
        assertThrows(IllegalArgumentException::class.java) { OccurrenceEvent.Snooze(Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { OccurrenceEvent.Snooze(Duration.ofMinutes(-5)) }
    }

    @Test
    fun policyValidation() {
        assertThrows(IllegalArgumentException::class.java) { SnoozePolicy(defaultSnooze = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { SnoozePolicy(ringTimeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { SnoozePolicy(maxRingBacks = -1) }
    }

    @Test
    fun defaultPolicyValues() {
        val policy = SnoozePolicy()

        assertThat(policy.defaultSnooze).isEqualTo(Duration.ofMinutes(5))
        assertThat(policy.ringTimeout).isEqualTo(Duration.ofSeconds(45))
        assertThat(policy.maxRingBacks).isEqualTo(3)
    }
}
