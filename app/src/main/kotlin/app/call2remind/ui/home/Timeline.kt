package app.call2remind.ui.home

import androidx.compose.runtime.Immutable
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.OccurrenceWithReminder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the secondary line of a timeline row says about its schedule. */
@Immutable
sealed interface RowCadence {
    /** A one-off at a time (calendar event, To Do reminder, Samsung reminder). */
    data object Once : RowCadence

    /** A date without its own time: rings at the source's default time ("no time set"). */
    data object NoTimeSet : RowCadence

    data class Habit(val days: Set<DayOfWeek>, val intervalWeeks: Int) : RowCadence

    /** Yearly (birthdays); [dayBefore] for the optional call the day before. */
    data class Yearly(val dayBefore: Boolean) : RowCadence
}

/** One line of the Up next timeline. */
@Immutable
data class TimelineRow(
    val occurrenceId: String,
    val reminderId: String,
    val title: String,
    val source: SourceType,
    val fireAt: Instant,
    val state: OccurrenceState,
    val cadence: RowCadence,
    /** The next call: its lamp is lit. */
    val isNext: Boolean = false,
) {
    val isSnoozed: Boolean get() = state == OccurrenceState.SNOOZED
    val isRinging: Boolean get() = state == OccurrenceState.RINGING

    /** Done / skipped earlier today (shown struck through, not swipeable). */
    val isFinished: Boolean get() = state == OccurrenceState.DONE || state == OccurrenceState.SKIPPED
}

enum class TimelineGroup { TODAY, TOMORROW, LATER }

@Immutable
data class TimelineSection(val group: TimelineGroup, val date: LocalDate, val rows: List<TimelineRow>)

/**
 * The Up next screen's content.
 *
 * @property ringing occurrences ringing right now (normally at most one): "Ringing now".
 * @property next the next call to come (not ringing): the caller-ID strip.
 */
@Immutable
data class UpNextModel(
    val ringing: List<TimelineRow> = emptyList(),
    val next: TimelineRow? = null,
    val sections: List<TimelineSection> = emptyList(),
) {
    /** Nothing lined up at all (the empty state). Finished rows alone don't count. */
    val isEmpty: Boolean get() = ringing.isEmpty() && next == null

    val rowCount: Int get() = sections.sumOf { it.rows.size }
}

/**
 * Builds the timeline from active occurrences (and today's finished ones), pure for tests.
 *
 * - RINGING rows go to [UpNextModel.ringing] only: they have a deadline alarm armed and are not
 *   "upcoming".
 * - SCHEDULED / SNOOZED rows are grouped by the local day of their `fireAt`: Today (including
 *   anything overdue), Tomorrow, Later. The earliest is [UpNextModel.next] and has its lamp lit.
 * - DONE / SKIPPED rows planned earlier today join Today, in time order, struck through.
 * - Occurrences whose reminder is gone, and ids in [hidden] (swiped, waiting for undo), are left out.
 */
object Timeline {
    fun build(
        upcoming: List<OccurrenceWithReminder>,
        finished: List<OccurrenceWithReminder>,
        now: Instant,
        zone: ZoneId,
        hidden: Set<String> = emptySet(),
    ): UpNextModel {
        val today = now.atZone(zone).toLocalDate()
        val active = upcoming
            .filter { it.reminder != null && it.occurrence.id !in hidden && it.occurrence.state.isActive }
            .map { it.toRow() }
        val ringing = active.filter { it.isRinging }
        val pending = active.filterNot { it.isRinging }.sortedWith(compareBy({ it.fireAt }, { it.occurrenceId }))
        val nextId = pending.firstOrNull()?.occurrenceId
        val rows = pending.map { if (it.occurrenceId == nextId) it.copy(isNext = true) else it }

        val doneToday = finished
            .filter { it.reminder != null && it.occurrence.id !in hidden }
            .filter { it.occurrence.state == OccurrenceState.DONE || it.occurrence.state == OccurrenceState.SKIPPED }
            .filter { it.occurrence.fireAt.atZone(zone).toLocalDate() == today && !it.occurrence.fireAt.isAfter(now) }
            .map { it.toRow() }

        val byGroup = LinkedHashMap<TimelineGroup, MutableList<TimelineRow>>()
        val dates = HashMap<TimelineGroup, LocalDate>()
        for (row in doneToday + rows) {
            val date = row.fireAt.atZone(zone).toLocalDate()
            val group = when {
                !date.isAfter(today) -> TimelineGroup.TODAY
                date == today.plusDays(1) -> TimelineGroup.TOMORROW
                else -> TimelineGroup.LATER
            }
            byGroup.getOrPut(group) { mutableListOf() } += row
            dates.getOrPut(group) {
                when (group) {
                    TimelineGroup.TODAY -> today
                    TimelineGroup.TOMORROW -> today.plusDays(1)
                    TimelineGroup.LATER -> date
                }
            }
        }
        val sections = TimelineGroup.entries.mapNotNull { group ->
            val list = byGroup[group] ?: return@mapNotNull null
            // Today: finished and pending rows interleave by time, so the day reads top to bottom.
            TimelineSection(group, dates.getValue(group), list.sortedWith(compareBy({ it.fireAt }, { it.occurrenceId })))
        }
        return UpNextModel(
            ringing = ringing,
            next = rows.firstOrNull(),
            sections = sections,
        )
    }

    private fun OccurrenceWithReminder.toRow(): TimelineRow {
        val reminder = requireNotNull(reminder)
        return TimelineRow(
            occurrenceId = occurrence.id,
            reminderId = reminder.id,
            title = reminder.title,
            source = reminder.sourceType,
            fireAt = occurrence.fireAt,
            state = occurrence.state,
            cadence = cadenceOf(reminder.schedule),
        )
    }

    fun cadenceOf(schedule: Schedule): RowCadence = when (schedule) {
        is Schedule.At -> RowCadence.Once
        is Schedule.DateOnly -> if (schedule.time == null) RowCadence.NoTimeSet else RowCadence.Once
        is Schedule.Recurring -> RowCadence.Habit(schedule.rule.daysOfWeek, schedule.rule.intervalWeeks)
        is Schedule.Annual -> RowCadence.Yearly(dayBefore = schedule.lead.days > 0)
    }
}
