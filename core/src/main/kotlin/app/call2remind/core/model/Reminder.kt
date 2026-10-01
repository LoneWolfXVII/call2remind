package app.call2remind.core.model

import java.time.ZoneId

/**
 * A normalized reminder from any source.
 *
 * @property id stable local id.
 * @property externalId id in the source system (event id, task id, contact id, habit id…).
 * Together with [sourceType] it forms the dedupe identity of the reminder.
 * @property notes optional body read out by TTS instead of the time.
 * @property ringtoneUri per-reminder ringtone override; `null` = source/app default.
 * @property enabled disabled reminders never produce occurrences.
 * @property zone zone used to resolve wall-clock schedules.
 */
data class Reminder(
    val id: String,
    val sourceType: SourceType,
    val externalId: String,
    val title: String,
    val schedule: Schedule,
    val zone: ZoneId,
    val notes: String? = null,
    val ringtoneUri: String? = null,
    val ttsEnabled: Boolean = true,
    val enabled: Boolean = true,
)
