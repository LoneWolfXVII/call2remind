package app.call2remind.core.birthday

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay

/**
 * Parses contact birthday strings (`ContactsContract.CommonDataKinds.Event.START_DATE`).
 *
 * Accepted (surrounding whitespace ignored):
 * - `yyyy-MM-dd` (e.g. `1990-05-17`)
 * - `yyyyMMdd` (e.g. `19900517`)
 * - `--MM-dd` (e.g. `--05-17`, year unknown)
 * - `--MMdd` (e.g. `--0517`, year unknown)
 *
 * Invalid dates (month 13, Apr 31, Feb 29 in a known non-leap year…) and anything else return
 * `null`. The placeholder years `0000` and `1604` (used by some sync adapters / iOS for "no year")
 * are treated as unknown.
 */
object BirthdayParser {
    private val WITH_YEAR_DASHED = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val WITH_YEAR_COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val NO_YEAR_DASHED = Regex("""^--(\d{2})-(\d{2})$""")
    private val NO_YEAR_COMPACT = Regex("""^--(\d{2})(\d{2})$""")
    private val PLACEHOLDER_YEARS = setOf(0, 1604)

    /** Parses [raw] or returns `null` if it is not a valid birthday. */
    fun parse(raw: String?): Birthday? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        (WITH_YEAR_DASHED.matchEntire(text) ?: WITH_YEAR_COMPACT.matchEntire(text))?.let { match ->
            val (year, month, day) = match.destructured
            return withYear(year.toInt(), month.toInt(), day.toInt())
        }
        (NO_YEAR_DASHED.matchEntire(text) ?: NO_YEAR_COMPACT.matchEntire(text))?.let { match ->
            val (month, day) = match.destructured
            return monthDayOrNull(month.toInt(), day.toInt())?.let { Birthday(it, null) }
        }
        return null
    }

    private fun withYear(year: Int, month: Int, day: Int): Birthday? {
        if (year in PLACEHOLDER_YEARS) return monthDayOrNull(month, day)?.let { Birthday(it, null) }
        return try {
            val date = LocalDate.of(year, month, day)
            Birthday(MonthDay.from(date), year)
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun monthDayOrNull(month: Int, day: Int): MonthDay? = try {
        MonthDay.of(month, day)
    } catch (e: DateTimeException) {
        null
    }
}
