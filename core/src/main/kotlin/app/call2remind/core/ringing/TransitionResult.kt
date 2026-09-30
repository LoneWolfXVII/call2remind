package app.call2remind.core.ringing

import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import java.time.Instant

/** Outcome of [OccurrenceStateMachine.transition]. */
sealed interface TransitionResult {

    /**
     * The event was accepted.
     *
     * @property occurrence the updated occurrence to persist.
     * @property nextFireAt when an alarm must be armed next (set only when the new state is
     * SNOOZED); `null` means no alarm is needed.
     * @property logEvent the ring log entry describing this transition.
     * @property from the state before the transition.
     */
    data class Transitioned(
        val occurrence: Occurrence,
        val nextFireAt: Instant?,
        val logEvent: RingLogEvent,
        val from: OccurrenceState,
    ) : TransitionResult {
        /** Shortcut for `occurrence.state`. */
        val state: OccurrenceState get() = occurrence.state
    }

    /**
     * The event is not allowed in [from] (or, for `Fire`, not yet: the occurrence is not due);
     * nothing must change.
     */
    data class InvalidTransition(
        val from: OccurrenceState,
        val event: OccurrenceEvent,
    ) : TransitionResult
}
