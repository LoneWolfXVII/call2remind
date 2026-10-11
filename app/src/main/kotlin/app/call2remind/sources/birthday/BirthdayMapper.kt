package app.call2remind.sources.birthday

import app.call2remind.core.birthday.Birthday
import app.call2remind.core.birthday.BirthdayParser
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale

/** A `ContactsContract.Data` row of mimetype `Event` with `TYPE_BIRTHDAY`. */
data class ContactBirthdayRow(
    val contactId: Long,
    val displayName: String?,
    /** `Event.START_DATE`, e.g. `1990-05-17` or `--05-17`. */
    val startDate: String?,
)

/**
 * Maps contact birthdays to annual reminders. Pure.
 *
 * - Dates go through [BirthdayParser]; unparseable or nameless rows are dropped.
 * - The same person synced from several accounts is deduped by **normalized display name +
 *   month-day** (case, accents and whitespace ignored). Of duplicates, one with a known year wins.
 * - `externalId = "<normalizedName>|<MM-dd>"`, stable across contact-id changes and accounts.
 * - Each birthday → [app.call2remind.core.model.Schedule.Annual] at the birthday default time.
 *   With [dayBefore], a second reminder (`externalId` + [DAY_BEFORE_SUFFIX]) rings a day earlier
 *   ([LeadOffset.days]`(1)`).
 * - Titles stay plain ("Asha's birthday"); the age is added by speech, from `sinceYear`.
 */
object BirthdayMapper {
    const val DAY_BEFORE_SUFFIX: String = "|day-before"

    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    fun map(rows: List<ContactBirthdayRow>, dayBefore: Boolean, zone: ZoneId): List<Reminder> {
        val unique = LinkedHashMap<String, Pair<String, Birthday>>()
        for (row in rows) {
            val name = row.displayName?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: continue
            val birthday = BirthdayParser.parse(row.startDate) ?: continue
            val key = "${normalizeName(name)}|${birthday.monthDay.toKey()}"
            val existing = unique[key]
            if (existing == null || (existing.second.year == null && birthday.year != null)) {
                unique[key] = name to birthday
            }
        }
        return unique.flatMap { (key, value) ->
            val (name, birthday) = value
            val onDay = reminder(key, titleFor(name), birthday, LeadOffset.NONE, zone)
            if (dayBefore) {
                listOf(onDay, reminder(key + DAY_BEFORE_SUFFIX, dayBeforeTitleFor(name), birthday, LeadOffset.days(1), zone))
            } else {
                listOf(onDay)
            }
        }
    }

    /** Lower-case, accent-free, single-spaced name used for dedupe and ids. */
    fun normalizeName(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)

    fun titleFor(name: String): String = "$name's birthday"

    fun dayBeforeTitleFor(name: String): String = "$name's birthday is tomorrow"

    private fun reminder(externalId: String, title: String, birthday: Birthday, lead: LeadOffset, zone: ZoneId) = Reminder(
        id = ReminderIds.of(SourceType.BIRTHDAY, externalId),
        sourceType = SourceType.BIRTHDAY,
        externalId = externalId,
        title = title,
        schedule = birthday.toSchedule(lead),
        zone = zone,
    )

    private fun java.time.MonthDay.toKey(): String = "%02d-%02d".format(Locale.ROOT, monthValue, dayOfMonth)
}
