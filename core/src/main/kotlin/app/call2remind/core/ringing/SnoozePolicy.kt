package app.call2remind.core.ringing

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import java.time.Duration

/**
 * Snooze / ring-back limits.
 *
 * @property defaultSnooze delay used by Decline and ring timeout (default 5 min).
 * @property ringTimeout how long a call rings unanswered before auto-snoozing (default 45 s).
 * @property maxRingBacks how many times an occurrence may ring again after its first ring
 * (default 3). Every snooze increments [Occurrence.ringBacks], whether declined, timed out or
 * chosen after answering. A Decline, ring timeout or unanswered Snooze that would exceed the limit
 * marks the occurrence MISSED instead; an explicit Snooze chosen **after answering** is exempt
 * from the limit (the user heard the reminder and asked to be called again), see [canSnooze].
 */
data class SnoozePolicy(
    val defaultSnooze: Duration = Duration.ofMinutes(5),
    val ringTimeout: Duration = Duration.ofSeconds(45),
    val maxRingBacks: Int = 3,
) {
    init {
        require(defaultSnooze > Duration.ZERO) { "defaultSnooze must be positive" }
        require(ringTimeout > Duration.ZERO) { "ringTimeout must be positive" }
        require(maxRingBacks >= 0) { "maxRingBacks must be >= 0" }
    }

    /** True while [occurrence] has ring-backs left under [maxRingBacks]. */
    fun hasRingBacksLeft(occurrence: Occurrence): Boolean = occurrence.ringBacks < maxRingBacks

    /**
     * Whether an explicit `Snooze` of [occurrence] would snooze it (rather than be invalid or mark
     * it MISSED). For the UI: show the snooze option only when this is true.
     * True for a RINGING occurrence that was answered, or that is unanswered with ring-backs left.
     */
    fun canSnooze(occurrence: Occurrence): Boolean =
        occurrence.state == OccurrenceState.RINGING &&
            (occurrence.answeredAt != null || hasRingBacksLeft(occurrence))
}
