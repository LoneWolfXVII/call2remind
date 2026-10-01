package app.call2remind.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import java.time.Instant

/** A configured reminder source (a calendar account, the Google Tasks account, habits…). */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val type: SourceType,
    val account: String?,
    val displayName: String?,
    val enabled: Boolean,
    val lastSyncAt: Instant?,
    /** Opaque incremental-sync cursor (e.g. Tasks `updatedMin`, Graph delta link). */
    val syncCursor: String?,
)

/**
 * A normalized reminder. [scheduleJson] holds the core `Schedule` serialized via
 * [app.call2remind.data.json.ScheduleJson]. `(sourceType, externalId)` is the dedupe identity.
 */
@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["sourceType", "externalId"], unique = true),
        Index(value = ["sourceId"]),
    ],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val sourceId: String?,
    val sourceType: SourceType,
    val externalId: String,
    val title: String,
    val notes: String?,
    val scheduleJson: String,
    val zoneId: String,
    val ringtoneUri: String?,
    val ttsEnabled: Boolean,
    val enabled: Boolean,
    val updatedAt: Instant,
)

/**
 * One concrete ring. Mirrors `app.call2remind.core.model.Occurrence` field by field.
 * The primary key [id] is the deterministic dedupe key (unique).
 */
@Entity(
    tableName = "occurrences",
    indices = [
        Index(value = ["state"]),
        Index(value = ["fireAt"]),
        Index(value = ["state", "fireAt"]),
        Index(value = ["reminderId"]),
    ],
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val sourceType: SourceType,
    val plannedAt: Instant,
    val fireAt: Instant,
    val state: OccurrenceState,
    val ringBacks: Int,
    val answeredAt: Instant?,
    val requestCode: Int,
)

/** Local ring log entry (debug / history screen). */
@Entity(
    tableName = "ring_log",
    indices = [Index(value = ["occurrenceId"]), Index(value = ["timestamp"])],
)
data class RingLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: String,
    val type: RingLogType,
    val timestamp: Instant,
    val reason: String?,
)

/** An occurrence with its reminder (null if the reminder was deleted; history keeps the row). */
data class OccurrenceWithReminderRow(
    @Embedded val occurrence: OccurrenceEntity,
    @Relation(parentColumn = "reminderId", entityColumn = "id")
    val reminder: ReminderEntity?,
)
