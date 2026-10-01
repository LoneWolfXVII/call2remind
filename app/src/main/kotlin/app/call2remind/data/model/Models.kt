package app.call2remind.data.model

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import java.time.Instant

/**
 * A configured reminder source, e.g. one calendar account or the in-app habits.
 *
 * @property syncCursor opaque incremental-sync state owned by the source's sync adapter.
 */
data class ReminderSource(
    val id: String,
    val type: SourceType,
    val account: String? = null,
    val displayName: String? = null,
    val enabled: Boolean = true,
    val lastSyncAt: Instant? = null,
    val syncCursor: String? = null,
)

/** An occurrence joined with its reminder; [reminder] is `null` if it has since been deleted. */
data class OccurrenceWithReminder(
    val occurrence: Occurrence,
    val reminder: Reminder?,
)

/** Stable local reminder ids. Sources should use these so re-syncs map onto the same row. */
object ReminderIds {
    /** `"<SOURCE_TYPE>:<externalId>"`. */
    fun of(sourceType: SourceType, externalId: String): String = "${sourceType.name}:$externalId"
}
