package app.call2remind.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.call2remind.R
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import java.time.DayOfWeek
import java.util.Locale

/** Which days a weekly habit rings on, bucketed for copy. */
sealed interface DaysSummary {
    data object Daily : DaysSummary

    data object Weekdays : DaysSummary

    data object Weekends : DaysSummary

    /** Specific days, in the locale's week order. */
    data class Specific(val days: List<DayOfWeek>) : DaysSummary
}

/** Pure schedule descriptions (unit tested). */
object ScheduleText {
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val ALL_DAYS = DayOfWeek.values().toSet()

    fun days(days: Set<DayOfWeek>, locale: Locale): DaysSummary = when (days) {
        ALL_DAYS -> DaysSummary.Daily
        WEEKDAYS -> DaysSummary.Weekdays
        WEEKEND -> DaysSummary.Weekends
        else -> DaysSummary.Specific(localeWeek(locale).filter { it in days })
    }

    /** What a lead offset means for the ring: at the moment, N minutes / hours before, or days before. */
    sealed interface Lead {
        data object AtTime : Lead

        data class MinutesBefore(val minutes: Long) : Lead

        data class DaysBefore(val days: Int) : Lead
    }

    fun lead(offset: LeadOffset): Lead = when {
        offset.isNone -> Lead.AtTime
        offset.days > 0 -> Lead.DaysBefore(offset.days)
        else -> Lead.MinutesBefore(offset.duration.toMinutes().coerceAtLeast(1))
    }

    /** True for schedules without a wall-clock time of their own (they ring at the source default). */
    fun usesDefaultTime(schedule: Schedule): Boolean = when (schedule) {
        is Schedule.DateOnly -> schedule.time == null
        is Schedule.Annual -> schedule.time == null
        is Schedule.At, is Schedule.Recurring -> false
    }
}

@Composable
fun daysText(summary: DaysSummary): String {
    val locale = currentLocale()
    return when (summary) {
        DaysSummary.Daily -> stringResource(R.string.days_daily)
        DaysSummary.Weekdays -> stringResource(R.string.days_weekdays)
        DaysSummary.Weekends -> stringResource(R.string.days_weekends)
        is DaysSummary.Specific -> summary.days.joinToString(" ") { weekdayShort(it, locale) }
    }
}

/** "Habit, daily" / "Habit, every 2 weeks on Tue Thu". */
@Composable
fun habitCadenceText(days: Set<DayOfWeek>, intervalWeeks: Int): String {
    val summary = daysText(ScheduleText.days(days, currentLocale()))
    return if (intervalWeeks <= 1) summary else stringResource(R.string.days_every_n_weeks, intervalWeeks, summary)
}

@Composable
fun leadText(lead: ScheduleText.Lead): String = when (lead) {
    ScheduleText.Lead.AtTime -> stringResource(R.string.lead_at_time)
    is ScheduleText.Lead.MinutesBefore ->
        if (lead.minutes % 60 == 0L) {
            pluralStringResource(R.plurals.lead_hours_before, (lead.minutes / 60).toInt(), lead.minutes / 60)
        } else {
            pluralStringResource(R.plurals.lead_minutes_before, lead.minutes.toInt(), lead.minutes)
        }
    is ScheduleText.Lead.DaysBefore -> pluralStringResource(R.plurals.lead_days_before, lead.days, lead.days)
}
