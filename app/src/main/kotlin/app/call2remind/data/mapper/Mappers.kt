package app.call2remind.data.mapper

import android.util.Log
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Reminder
import app.call2remind.data.db.OccurrenceEntity
import app.call2remind.data.db.OccurrenceWithReminderRow
import app.call2remind.data.db.ReminderEntity
import app.call2remind.data.db.RingLogEntity
import app.call2remind.data.db.SourceEntity
import app.call2remind.data.json.ScheduleJson
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.data.model.ReminderSource
import java.time.Instant
import java.time.ZoneId

private const val TAG = "Mappers"

fun Reminder.toEntity(sourceId: String?, updatedAt: Instant): ReminderEntity = ReminderEntity(
    id = id,
    sourceId = sourceId,
    sourceType = sourceType,
    externalId = externalId,
    title = title,
    notes = notes,
    scheduleJson = ScheduleJson.encode(schedule),
    zoneId = zone.id,
    ringtoneUri = ringtoneUri,
    ttsEnabled = ttsEnabled,
    enabled = enabled,
    updatedAt = updatedAt,
)

/** Maps back to the core model; throws if the stored schedule or zone is unreadable. */
fun ReminderEntity.toModel(): Reminder = Reminder(
    id = id,
    sourceType = sourceType,
    externalId = externalId,
    title = title,
    schedule = ScheduleJson.decode(scheduleJson),
    zone = ZoneId.of(zoneId),
    notes = notes,
    ringtoneUri = ringtoneUri,
    ttsEnabled = ttsEnabled,
    enabled = enabled,
)

/** Like [toModel] but skips (and logs) a corrupt row instead of failing the whole query. */
@Suppress("TooGenericExceptionCaught")
fun ReminderEntity.toModelOrNull(): Reminder? = try {
    toModel()
} catch (e: RuntimeException) {
    Log.w(TAG, "Skipping unreadable reminder $id", e)
    null
}

fun Occurrence.toEntity(): OccurrenceEntity = OccurrenceEntity(
    id = id,
    reminderId = reminderId,
    sourceType = sourceType,
    plannedAt = plannedAt,
    fireAt = fireAt,
    state = state,
    ringBacks = ringBacks,
    answeredAt = answeredAt,
    requestCode = requestCode,
)

fun OccurrenceEntity.toModel(): Occurrence = Occurrence(
    id = id,
    reminderId = reminderId,
    sourceType = sourceType,
    plannedAt = plannedAt,
    fireAt = fireAt,
    state = state,
    ringBacks = ringBacks,
    answeredAt = answeredAt,
    requestCode = requestCode,
)

fun OccurrenceWithReminderRow.toModel(): OccurrenceWithReminder =
    OccurrenceWithReminder(occurrence = occurrence.toModel(), reminder = reminder?.toModelOrNull())

fun RingLogEvent.toEntity(): RingLogEntity = RingLogEntity(
    occurrenceId = occurrenceId,
    type = type,
    timestamp = timestamp,
    reason = reason,
)

fun RingLogEntity.toModel(): RingLogEvent = RingLogEvent(
    occurrenceId = occurrenceId,
    type = type,
    timestamp = timestamp,
    reason = reason,
)

fun ReminderSource.toEntity(): SourceEntity = SourceEntity(
    id = id,
    type = type,
    account = account,
    displayName = displayName,
    enabled = enabled,
    lastSyncAt = lastSyncAt,
    syncCursor = syncCursor,
)

fun SourceEntity.toModel(): ReminderSource = ReminderSource(
    id = id,
    type = type,
    account = account,
    displayName = displayName,
    enabled = enabled,
    lastSyncAt = lastSyncAt,
    syncCursor = syncCursor,
)
