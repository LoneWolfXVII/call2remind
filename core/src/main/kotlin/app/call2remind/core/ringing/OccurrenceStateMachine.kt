package app.call2remind.core.ringing

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
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Pure transition function for an [Occurrence]'s lifecycle.
 *
 * | From                | Event                         | To                                  |
 * |---------------------|-------------------------------|-------------------------------------|
 * | SCHEDULED, SNOOZED  | Fire                          | RINGING (fireAt = now, unanswered)  |
 * | RINGING unanswered  | Answer                        | RINGING (answeredAt = now)          |
 * | RINGING             | Snooze(d), Decline            | SNOOZED at now + d, or MISSED \*    |
 * | RINGING unanswered  | RingTimeout                   | SNOOZED at now + default, or MISSED \* |
 * | SCHEDULED, SNOOZED, RINGING, MISSED | Done          | DONE                                |
 * | SCHEDULED, SNOOZED, RINGING | Skip                  | SKIPPED                             |
 * | SCHEDULED, SNOOZED, RINGING | MarkMissed            | MISSED                              |
 *
 * \* MISSED when `ringBacks` has already reached [SnoozePolicy.maxRingBacks].
 * Anything else is an [TransitionResult.InvalidTransition]. "Now" comes from [clock].
 */
class OccurrenceStateMachine(
    private val clock: Clock,
    private val policy: SnoozePolicy = SnoozePolicy(),
) {

    /** Applies [event] to [occurrence]. */
    fun transition(occurrence: Occurrence, event: OccurrenceEvent): TransitionResult {
        val now = clock.instant()
        val state = occurrence.state
        val ringing = state == RINGING
        val unanswered = ringing && occurrence.answeredAt == null
        val pending = state == SCHEDULED || state == SNOOZED
        val invalid = TransitionResult.InvalidTransition(state, event)

        return when (event) {
            OccurrenceEvent.Fire ->
                if (pending) {
                    accept(occurrence.copy(state = RINGING, fireAt = now, answeredAt = null), RingLogType.FIRED, now)
                } else {
                    invalid
                }

            OccurrenceEvent.Answer ->
                if (unanswered) accept(occurrence.copy(answeredAt = now), RingLogType.ANSWERED, now) else invalid

            is OccurrenceEvent.Snooze ->
                if (ringing) snooze(occurrence, event.duration, now, RingLogEvent.REASON_USER_SNOOZE) else invalid

            OccurrenceEvent.Decline ->
                if (ringing) snooze(occurrence, policy.defaultSnooze, now, RingLogEvent.REASON_DECLINED) else invalid

            OccurrenceEvent.RingTimeout ->
                if (unanswered) {
                    snooze(occurrence, policy.defaultSnooze, now, RingLogEvent.REASON_RING_TIMEOUT)
                } else {
                    invalid
                }

            OccurrenceEvent.Done ->
                if (pending || ringing || state == MISSED) {
                    accept(occurrence.copy(state = DONE, answeredAt = null), RingLogType.DONE, now)
                } else {
                    invalid
                }

            OccurrenceEvent.Skip ->
                if (pending || ringing) {
                    accept(occurrence.copy(state = SKIPPED, answeredAt = null), RingLogType.SKIPPED, now)
                } else {
                    invalid
                }

            OccurrenceEvent.MarkMissed ->
                if (pending || ringing) {
                    accept(occurrence.copy(state = MISSED, answeredAt = null), RingLogType.MISSED, now)
                } else {
                    invalid
                }
        }
    }

    private fun snooze(occurrence: Occurrence, delay: Duration, now: Instant, reason: String): TransitionResult {
        if (occurrence.ringBacks >= policy.maxRingBacks) {
            val missed = occurrence.copy(state = MISSED, answeredAt = null)
            return accept(missed, RingLogType.MISSED, now, RingLogEvent.REASON_MAX_RING_BACKS)
        }
        val nextFireAt = now.plus(delay)
        val snoozed = occurrence.copy(
            state = SNOOZED,
            fireAt = nextFireAt,
            ringBacks = occurrence.ringBacks + 1,
            answeredAt = null,
        )
        return TransitionResult.Transitioned(
            occurrence = snoozed,
            nextFireAt = nextFireAt,
            logEvent = RingLogEvent(occurrence.id, RingLogType.SNOOZED, now, reason),
        )
    }

    private fun accept(
        updated: Occurrence,
        type: RingLogType,
        now: Instant,
        reason: String? = null,
    ): TransitionResult = TransitionResult.Transitioned(
        occurrence = updated,
        nextFireAt = null,
        logEvent = RingLogEvent(updated.id, type, now, reason),
    )
}
