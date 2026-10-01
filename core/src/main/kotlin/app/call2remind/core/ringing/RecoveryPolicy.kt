package app.call2remind.core.ringing

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import java.time.Duration
import java.time.Instant

/** What recovery wants done with one occurrence. The app feeds these into the state machine. */
sealed interface RecoveryAction {
    val occurrence: Occurrence

    /** Overdue but recent enough: ring it now (send `Fire`, via the ring queue). */
    data class RingNow(override val occurrence: Occurrence, val lateBy: Duration) : RecoveryAction

    /** Too late to be useful: send `MarkMissed`. */
    data class MarkMissed(override val occurrence: Occurrence, val lateBy: Duration) : RecoveryAction

    /** Stuck ringing unanswered (process died mid-ring): send `RingTimeout`. */
    data class TimeOutRinging(override val occurrence: Occurrence) : RecoveryAction

    /** Answered but never finished (process died during the call): send `Done`. */
    data class FinishAnswered(override val occurrence: Occurrence) : RecoveryAction
}

/**
 * Boot / watchdog / reconciliation policy for occurrences that should already have rung.
 *
 * - SCHEDULED or SNOOZED with `fireAt < now` (strictly before; an occurrence due exactly now is
 *   left to its alarm): late by less than [ringIfLateWithin] → [RecoveryAction.RingNow];
 *   late by [ringIfLateWithin] or more → [RecoveryAction.MarkMissed].
 * - RINGING, unanswered, with `now - fireAt >= ringIfLateWithin` (stuck across a long outage;
 *   `fireAt` is the ring start) → [RecoveryAction.MarkMissed], so it is not snoozed hours late;
 *   otherwise with `now - fireAt >= ringTimeout + ringingGrace` → [RecoveryAction.TimeOutRinging].
 * - RINGING, answered, with `now - answeredAt >= answeredStaleAfter` →
 *   [RecoveryAction.FinishAnswered] (the user already heard it).
 *
 * Everything else produces no action. Results are ordered like [RingQueue.ORDER].
 */
data class RecoveryPolicy(
    val ringIfLateWithin: Duration = Duration.ofHours(2),
    val snoozePolicy: SnoozePolicy = SnoozePolicy(),
    val ringingGrace: Duration = Duration.ofSeconds(30),
    val answeredStaleAfter: Duration = Duration.ofMinutes(15),
) {
    /** Actions for [occurrences] at [now]. */
    fun recover(occurrences: Collection<Occurrence>, now: Instant): List<RecoveryAction> =
        occurrences
            .sortedWith(RingQueue.ORDER)
            .mapNotNull { actionFor(it, now) }

    /**
     * When recovery will act on a RINGING [occurrence] if it is still ringing then: the ring
     * timeout plus [ringingGrace] after the ring started (unanswered), or [answeredStaleAfter]
     * after it was answered. `null` for any other state. Arm an alarm at (or just after) this
     * instant so a ring whose service died is recovered promptly, without waiting for the watchdog.
     */
    fun deadline(occurrence: Occurrence): Instant? {
        if (occurrence.state != OccurrenceState.RINGING) return null
        val answeredAt = occurrence.answeredAt
        return if (answeredAt == null) {
            occurrence.fireAt.plus(snoozePolicy.ringTimeout).plus(ringingGrace)
        } else {
            answeredAt.plus(answeredStaleAfter)
        }
    }

    /** Action for a single [occurrence] at [now], or `null` if it needs none. */
    fun actionFor(occurrence: Occurrence, now: Instant): RecoveryAction? = when (occurrence.state) {
        OccurrenceState.SCHEDULED, OccurrenceState.SNOOZED -> {
            if (occurrence.fireAt.isBefore(now)) {
                val late = Duration.between(occurrence.fireAt, now)
                if (late < ringIfLateWithin) {
                    RecoveryAction.RingNow(occurrence, late)
                } else {
                    RecoveryAction.MarkMissed(occurrence, late)
                }
            } else {
                null
            }
        }
        OccurrenceState.RINGING -> {
            val answeredAt = occurrence.answeredAt
            if (answeredAt == null) {
                val ringingFor = Duration.between(occurrence.fireAt, now)
                if (ringingFor >= ringIfLateWithin) {
                    RecoveryAction.MarkMissed(occurrence, ringingFor)
                } else if (ringingFor >= snoozePolicy.ringTimeout.plus(ringingGrace)) {
                    RecoveryAction.TimeOutRinging(occurrence)
                } else {
                    null
                }
            } else if (Duration.between(answeredAt, now) >= answeredStaleAfter) {
                RecoveryAction.FinishAnswered(occurrence)
            } else {
                null
            }
        }
        OccurrenceState.DONE, OccurrenceState.MISSED, OccurrenceState.SKIPPED -> null
    }
}
