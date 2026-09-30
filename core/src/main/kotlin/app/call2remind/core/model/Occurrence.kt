package app.call2remind.core.model

import app.call2remind.core.planning.RequestCodes
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One concrete ring of a [Reminder].
 *
 * @property id deterministic id, see [OccurrenceKey.id].
 * @property plannedAt the originally planned ring instant (part of the dedupe key; never changes).
 * Millisecond precision (see [scheduled]), matching the id and Room's storage.
 * @property fireAt when the alarm should (next) ring. Equals [plannedAt] until snoozed; set to the
 * actual ring start on `Fire`.
 * @property ringBacks how many times it has been snoozed/declined/timed out so far.
 * @property answeredAt when the current ring was answered; `null` if not answered.
 * @property requestCode stable `PendingIntent` request code, see [RequestCodes.forOccurrence].
 */
data class Occurrence(
    val id: String,
    val reminderId: String,
    val sourceType: SourceType,
    val plannedAt: Instant,
    val fireAt: Instant,
    val state: OccurrenceState,
    val ringBacks: Int = 0,
    val answeredAt: Instant? = null,
    val requestCode: Int = RequestCodes.forOccurrence(id),
) {
    companion object {
        /**
         * A fresh [OccurrenceState.SCHEDULED] occurrence of [reminder] at [plannedAt], truncated to
         * milliseconds so that the in-memory occurrence equals what a Room round trip returns.
         */
        fun scheduled(reminder: Reminder, plannedAt: Instant): Occurrence {
            val planned = plannedAt.truncatedTo(ChronoUnit.MILLIS)
            val id = OccurrenceKey.of(reminder, planned).id
            return Occurrence(
                id = id,
                reminderId = reminder.id,
                sourceType = reminder.sourceType,
                plannedAt = planned,
                fireAt = planned,
                state = OccurrenceState.SCHEDULED,
            )
        }
    }
}
