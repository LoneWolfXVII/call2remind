package app.call2remind.core.speech

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the short sentence read by TTS after the user answers.
 *
 * Format: `Reminder: <title>. <detail>` where detail is, in order of preference:
 * 1. the reminder's notes (whitespace collapsed, truncated to [MAX_NOTES_CHARS] with `…`);
 * 2. for timed schedules ([Schedule.At], [Schedule.Recurring]) the event time as `h:mm a`
 *    in [zone] and [locale] (the event time, i.e. before the lead offset is applied);
 * 3. for date-only schedules with a day lead: `Tomorrow.` or `In N days.`;
 * 4. nothing.
 *
 * The phrase template is English; [locale] only affects time formatting.
 */
object SpeechText {
    /** Longest notes excerpt read aloud. */
    const val MAX_NOTES_CHARS: Int = 160

    private const val UNTITLED = "Untitled"
    private val WHITESPACE = Regex("""\s+""")

    /** Speech text for [occurrence] of [reminder]. */
    fun build(
        reminder: Reminder,
        occurrence: Occurrence,
        zone: ZoneId = reminder.zone,
        locale: Locale = Locale.getDefault(),
    ): String = build(reminder, occurrence.plannedAt, zone, locale)

    /** Speech text for [reminder] ringing (as planned) at [plannedAt]. */
    fun build(reminder: Reminder, plannedAt: Instant, zone: ZoneId, locale: Locale): String {
        val title = clean(reminder.title).ifEmpty { UNTITLED }
        val head = "Reminder: ${sentence(title)}"
        val detail = detail(reminder, plannedAt, zone, locale) ?: return head
        return "$head ${sentence(detail)}"
    }

    private fun detail(reminder: Reminder, plannedAt: Instant, zone: ZoneId, locale: Locale): String? {
        val notes = clean(reminder.notes.orEmpty())
        if (notes.isNotEmpty()) return truncate(notes)
        val schedule = reminder.schedule
        return when (schedule) {
            is Schedule.At, is Schedule.Recurring -> {
                val eventTime = schedule.lead.eventTimeFor(plannedAt, reminder.zone)
                DateTimeFormatter.ofPattern("h:mm a", locale).format(eventTime.atZone(zone))
            }
            is Schedule.DateOnly, is Schedule.Annual -> when (val days = schedule.lead.days) {
                0 -> null
                1 -> "Tomorrow"
                else -> "In $days days"
            }
        }
    }

    private fun clean(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun sentence(text: String): String =
        if (text.last() in ".!?…") text else "$text."

    private fun truncate(text: String): String {
        if (text.length <= MAX_NOTES_CHARS) return text
        val cut = text.substring(0, MAX_NOTES_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        val base = if (lastSpace > MAX_NOTES_CHARS / 2) cut.substring(0, lastSpace) else cut
        return base.trimEnd() + "…"
    }
}
