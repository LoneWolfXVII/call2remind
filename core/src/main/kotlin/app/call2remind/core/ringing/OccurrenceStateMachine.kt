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
 * | SCHEDULED, SNOOZED, due \*\* | Fire                  | RINGING (fireAt = now, unanswered)  |
 * | RINGING unanswered  | Answer                        | RINGING (answeredAt = now)          |
 * | RINGING             | Snooze(d), Decline            | SNOOZED at now + d, or MISSED \*    |
 * | RINGING unanswered  | RingTimeout                   | SNOOZED at now + default, or MISSED \* |
 * | SCHEDULED, SNOOZED, RINGING, MISSED | Done          | DONE                                |
 * | SCHEDULED, SNOOZED, RINGING | Skip                  | SKIPPED                             |
 * | SCHEDULED, SNOOZED, RINGING | MarkMissed            | MISSED                              |
 *
 * \* MISSED when `ringBacks` has already reached [SnoozePolicy.maxRingBacks], except for an
 * explicit Snooze after answering, which is never capped (see [SnoozePolicy.canSnooze]).
 *
 * \*\* Due means `fireAt <= now + ` [FIRE_TOLERANCE]; firing earlier is invalid (a stale or
 * mis-set alarm must not ring an occurrence ahead of time).
 *
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
                if (pending && !occurrence.fireAt.isAfter(now.plus(FIRE_TOLERANCE))) {
                    val started = occurrence.copy(state = RINGING, fireAt = now, answeredAt = null)
                    accept(state, started, RingLogType.FIRED, now)
                } else {
                    invalid
                }

            OccurrenceEvent.Answer ->
                if (unanswered) accept(state, occurrence.copy(answeredAt = now), RingLogType.ANSWERED, now) else invalid

            is OccurrenceEvent.Snooze ->
                if (ringing) {
                    val capped = occurrence.answeredAt == null
                    snooze(occurrence, event.duration, now, RingLogEvent.REASON_USER_SNOOZE, capped)
                } else {
                    invalid
                }

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
                    accept(state, occurrence.copy(state = DONE, answeredAt = null), RingLogType.DONE, now)
                } else {
                    invalid
                }

            OccurrenceEvent.Skip ->
                if (pending || ringing) {
                    accept(state, occurrence.copy(state = SKIPPED, answeredAt = null), RingLogType.SKIPPED, now)
                } else {
                    invalid
                }

            OccurrenceEvent.MarkMissed ->
                if (pending || ringing) {
                    accept(state, occurrence.copy(state = MISSED, answeredAt = null), RingLogType.MISSED, now)
                } else {
                    invalid
                }
        }
    }

    /** See [SnoozePolicy.canSnooze]. */
    fun canSnooze(occurrence: Occurrence): Boolean = policy.canSnooze(occurrence)

    private fun snooze(
        occurrence: Occurrence,
        delay: Duration,
        now: Instant,
        reason: String,
        capped: Boolean = true,
    ): TransitionResult {
        if (capped && !policy.hasRingBacksLeft(occurrence)) {
            val missed = occurrence.copy(state = MISSED, answeredAt = null)
            return accept(occurrence.state, missed, RingLogType.MISSED, now, RingLogEvent.REASON_MAX_RING_BACKS)
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
            from = occurrence.state,
        )
    }

    private fun accept(
        from: OccurrenceState,
        updated: Occurrence,
        type: RingLogType,
        now: Instant,
        reason: String? = null,
    ): TransitionResult = TransitionResult.Transitioned(
        occurrence = updated,
        nextFireAt = null,
        logEvent = RingLogEvent(updated.id, type, now, reason),
        from = from,
    )

    companion object {
        /** How early (clock skew, alarm batching) `Fire` may arrive before `fireAt`. */
        val FIRE_TOLERANCE: Duration = Duration.ofSeconds(1)
    }
}
