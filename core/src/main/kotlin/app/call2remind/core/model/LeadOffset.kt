package app.call2remind.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * How long before the scheduled moment a reminder should ring, e.g. a calendar event's
 * "10 minutes before" reminder or a birthday's "day before" call.
 *
 * The offset is applied in two steps: first [days] calendar days are subtracted in the reminder's
 * zone (keeping the wall-clock time, so "day before at 09:00" stays 09:00 across DST changes),
 * then the exact [duration] is subtracted from the resulting instant.
 *
 * Both parts must be non-negative: a lead offset never moves a ring *after* the base moment.
 */
data class LeadOffset(
    val days: Int = 0,
    val duration: Duration = Duration.ZERO,
) {
    init {
        require(days >= 0) { "days must be >= 0, was $days" }
        require(!duration.isNegative) { "duration must be >= 0, was $duration" }
    }

    /** True when this offset does not move the ring time. */
    val isNone: Boolean get() = days == 0 && duration.isZero

    /**
     * An upper bound for how far before `base` [applyTo] can land, whatever the zone
     * (a calendar day is at most 25 h long across DST changes).
     */
    val maxSpan: Duration get() = Duration.ofHours(MAX_HOURS_PER_DAY * days).plus(duration)

    /** Returns the ring instant for an event that happens at [base] in [zone]. */
    fun applyTo(base: Instant, zone: ZoneId): Instant {
        val shifted = if (days == 0) base else base.atZone(zone).minusDays(days.toLong()).toInstant()
        return shifted.minus(duration)
    }

    /** Inverse of [applyTo]: recovers the event moment from a ring instant. */
    fun eventTimeFor(fireAt: Instant, zone: ZoneId): Instant {
        val unshifted = fireAt.plus(duration)
        return if (days == 0) unshifted else unshifted.atZone(zone).plusDays(days.toLong()).toInstant()
    }

    companion object {
        private const val MAX_HOURS_PER_DAY = 25L

        /** No offset: ring exactly at the scheduled moment. */
        val NONE: LeadOffset = LeadOffset()

        /** Ring [minutes] minutes before the scheduled moment. */
        fun minutes(minutes: Long): LeadOffset = LeadOffset(duration = Duration.ofMinutes(minutes))

        /** Ring [days] calendar days before, at the same wall-clock time. */
        fun days(days: Int): LeadOffset = LeadOffset(days = days)
    }
}
