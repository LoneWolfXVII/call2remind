package app.call2remind.core.model

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Dedupe identity of an [Occurrence]: source + external id + planned fire instant.
 *
 * [id] is a deterministic string form, used as the occurrence primary key so that planning the
 * same reminder twice always yields the same id. Layout: `SOURCE|epochMillis|externalId`;
 * the external id comes last so arbitrary characters in it cannot make two keys collide.
 *
 * The id has millisecond precision (like Room's `Instant` column), so keys should be built with
 * [of], which truncates [plannedAt] to milliseconds; two keys whose instants differ only below a
 * millisecond have the same [id].
 */
data class OccurrenceKey(
    val sourceType: SourceType,
    val externalId: String,
    val plannedAt: Instant,
) {
    /** Deterministic occurrence id for this key. */
    val id: String get() = "${sourceType.name}$SEPARATOR${plannedAt.toEpochMilli()}$SEPARATOR$externalId"

    companion object {
        private const val SEPARATOR = '|'

        /** Key for [reminder] ringing (as planned) at [plannedAt] (truncated to milliseconds). */
        fun of(reminder: Reminder, plannedAt: Instant): OccurrenceKey =
            OccurrenceKey(reminder.sourceType, reminder.externalId, plannedAt.truncatedTo(ChronoUnit.MILLIS))

        /**
         * Parses an [id] produced by [OccurrenceKey.id] back into a key, or returns `null` if it is
         * not in that layout.
         */
        fun parse(id: String): OccurrenceKey? {
            val parts = id.split(SEPARATOR, limit = 3)
            if (parts.size != 3) return null
            val sourceType = SourceType.entries.firstOrNull { it.name == parts[0] } ?: return null
            val millis = parts[1].toLongOrNull() ?: return null
            return OccurrenceKey(sourceType, parts[2], Instant.ofEpochMilli(millis))
        }
    }
}
