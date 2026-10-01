package app.call2remind.core.birthday

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import java.time.LocalDate
import java.time.MonthDay

/**
 * A parsed birthday. [year] is `null` when the source omits it.
 */
data class Birthday(val monthDay: MonthDay, val year: Int? = null) {

    /**
     * Age in whole years on [date], or `null` if the year is unknown or [date] is before the
     * birth. Feb 29 birthdays count as Feb 28 in non-leap years.
     */
    fun ageOn(date: LocalDate): Int? {
        val birthYear = year ?: return null
        val birthdayThisYear = monthDay.atYear(date.year)
        val age = date.year - birthYear - if (date.isBefore(birthdayThisYear)) 1 else 0
        return if (age < 0) null else age
    }

    /** Age turned on the birthday in [year], or `null` if the birth year is unknown or later. */
    fun ageTurningIn(year: Int): Int? {
        val birthYear = this.year ?: return null
        val age = year - birthYear
        return if (age < 0) null else age
    }

    /** An annual schedule for this birthday, optionally with a lead (e.g. day-before call). */
    fun toSchedule(lead: LeadOffset = LeadOffset.NONE): Schedule.Annual =
        Schedule.Annual(monthDay = monthDay, sinceYear = year, lead = lead)
}
