package app.call2remind.core.ringing

import java.time.Duration

/**
 * Snooze / ring-back limits.
 *
 * @property defaultSnooze delay used by Decline and ring timeout (default 5 min).
 * @property ringTimeout how long a call rings unanswered before auto-snoozing (default 45 s).
 * @property maxRingBacks how many times an occurrence may ring again after its first ring
 * (default 3). Every snooze counts, whether declined, timed out or chosen after answering;
 * the snooze that would exceed the limit marks the occurrence MISSED instead.
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
}
