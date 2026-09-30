package app.call2remind.core.model

import java.time.Instant

/**
 * Dedupe identity of an [Occurrence]: source + external id + planned fire instant.
 *
 * [id] is a deterministic string form, used as the occurrence primary key so that planning the
 * same reminder twice always yields the same id. Layout: `SOURCE|epochMillis|externalId`;
 * the external id comes last so arbitrary characters in it cannot make two keys collide.
 */
data class OccurrenceKey(
    val sourceType: SourceType,
    val externalId: String,
    val plannedAt: Instant,
) {
    /** Deterministic occurrence id for this key. */
    val id: String get() = "${sourceType.name}|${plannedAt.toEpochMilli()}|$externalId"

    companion object {
        /** Key for [reminder] ringing (as planned) at [plannedAt]. */
        fun of(reminder: Reminder, plannedAt: Instant): OccurrenceKey =
            OccurrenceKey(reminder.sourceType, reminder.externalId, plannedAt)
    }
}
