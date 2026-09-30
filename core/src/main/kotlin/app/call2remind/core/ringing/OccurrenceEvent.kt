package app.call2remind.core.ringing

import java.time.Duration

/** Inputs to [OccurrenceStateMachine]. */
sealed interface OccurrenceEvent {
    /** The alarm went off and the call starts ringing. */
    data object Fire : OccurrenceEvent

    /** The user picked up. */
    data object Answer : OccurrenceEvent

    /** The user marked it done. */
    data object Done : OccurrenceEvent

    /** The user chose to be called again after [duration] (must be positive). */
    data class Snooze(val duration: Duration) : OccurrenceEvent {
        init {
            require(duration > Duration.ZERO) { "snooze duration must be positive, was $duration" }
        }
    }

    /** The user rejected the call: snooze with the policy's default duration. */
    data object Decline : OccurrenceEvent

    /** Nobody answered within the ring timeout: auto-snooze with the default duration. */
    data object RingTimeout : OccurrenceEvent

    /** The user skipped this occurrence. */
    data object Skip : OccurrenceEvent

    /** Give up on this occurrence (e.g. recovery found it too late). */
    data object MarkMissed : OccurrenceEvent
}
