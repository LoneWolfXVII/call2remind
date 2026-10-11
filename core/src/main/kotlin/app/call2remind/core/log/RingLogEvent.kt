package app.call2remind.core.log

import java.time.Instant

/** Kind of entry in the local ring log. */
enum class RingLogType {
    FIRED,
    ANSWERED,
    SNOOZED,
    DONE,
    MISSED,
    SKIPPED,

    /** Ring postponed, e.g. the user is in a real phone call. */
    DEFERRED,

    /** Ringing could not start (e.g. foreground service start refused). */
    FAILED,
}

/**
 * One entry of the local ring log shown on the debug/history screen.
 *
 * @property reason optional machine-friendly detail, e.g. [REASON_RING_TIMEOUT].
 */
data class RingLogEvent(
    val occurrenceId: String,
    val type: RingLogType,
    val timestamp: Instant,
    val reason: String? = null,
) {
    companion object {
        const val REASON_DECLINED = "declined"
        const val REASON_USER_SNOOZE = "user_snooze"
        const val REASON_RING_TIMEOUT = "ring_timeout"
        const val REASON_MAX_RING_BACKS = "max_ring_backs"
        const val REASON_TOO_LATE = "too_late"
        const val REASON_IN_CALL = "in_call"
    }
}
