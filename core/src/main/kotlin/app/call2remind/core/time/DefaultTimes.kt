package app.call2remind.core.time

import app.call2remind.core.model.SourceType
import java.time.LocalTime

/**
 * Wall-clock time at which date-only items (tasks, all-day events, birthdays…) ring, per source.
 * Immutable; use [copy] or [with] to override. Editable in Settings.
 *
 * @property calendarAllDay time for all-day calendar events.
 * @property habit fallback only; habits normally carry their own times.
 */
data class DefaultTimes(
    val calendarAllDay: LocalTime = NINE_AM,
    val googleTasks: LocalTime = NINE_AM,
    val samsungReminder: LocalTime = NINE_AM,
    val msTodo: LocalTime = NINE_AM,
    val birthday: LocalTime = NINE_AM,
    val habit: LocalTime = NINE_AM,
) {
    /** Default time for [type]. */
    fun forSource(type: SourceType): LocalTime = when (type) {
        SourceType.CALENDAR -> calendarAllDay
        SourceType.GOOGLE_TASKS -> googleTasks
        SourceType.SAMSUNG_REMINDER -> samsungReminder
        SourceType.MS_TODO -> msTodo
        SourceType.BIRTHDAY -> birthday
        SourceType.HABIT -> habit
    }

    /** Operator alias for [forSource]. */
    operator fun get(type: SourceType): LocalTime = forSource(type)

    /** Returns a copy with [type]'s default time replaced by [time]. */
    fun with(type: SourceType, time: LocalTime): DefaultTimes = when (type) {
        SourceType.CALENDAR -> copy(calendarAllDay = time)
        SourceType.GOOGLE_TASKS -> copy(googleTasks = time)
        SourceType.SAMSUNG_REMINDER -> copy(samsungReminder = time)
        SourceType.MS_TODO -> copy(msTodo = time)
        SourceType.BIRTHDAY -> copy(birthday = time)
        SourceType.HABIT -> copy(habit = time)
    }

    /** All values keyed by source, e.g. for persisting to DataStore. */
    fun toMap(): Map<SourceType, LocalTime> = SourceType.entries.associateWith { forSource(it) }

    companion object {
        private val NINE_AM: LocalTime = LocalTime.of(9, 0)

        /** Out-of-the-box defaults: 09:00 everywhere. */
        val DEFAULT: DefaultTimes = DefaultTimes()

        /** Builds from a (possibly partial) map; missing sources keep [DEFAULT]. */
        fun of(times: Map<SourceType, LocalTime>): DefaultTimes =
            times.entries.fold(DEFAULT) { acc, (type, time) -> acc.with(type, time) }
    }
}
