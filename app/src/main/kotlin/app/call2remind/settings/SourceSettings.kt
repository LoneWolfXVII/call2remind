package app.call2remind.settings

import app.call2remind.core.model.SourceType

/**
 * Per-source sync options (Sources screen).
 *
 * @property enabled source types the user has turned on. A cloud source that is enabled but not
 * connected reports "not connected"; turning a pull source off deletes its synced reminders.
 * Samsung Reminders is opt-in (it also needs notification access).
 * @property calendarDaysAhead how far ahead calendar instances are read (the planner itself only
 * arms 48 h; the rest is kept in Room for the timeline).
 * @property excludedCalendarIds `CalendarContract.Calendars._ID`s the user excluded. New calendars
 * are included by default.
 * @property birthdayDayBefore also call the day before each birthday.
 * @property samsungPackages packages whose notifications the Samsung listener turns into calls.
 */
data class SourceSettings(
    val enabled: Set<SourceType> = DEFAULT_ENABLED,
    val calendarDaysAhead: Int = DEFAULT_CALENDAR_DAYS,
    val excludedCalendarIds: Set<Long> = emptySet(),
    val birthdayDayBefore: Boolean = false,
    val samsungPackages: Set<String> = DEFAULT_SAMSUNG_PACKAGES,
) {
    fun isEnabled(type: SourceType): Boolean = type in enabled

    /** Returns a copy with [type] turned on or off. */
    fun withEnabled(type: SourceType, on: Boolean): SourceSettings =
        copy(enabled = if (on) enabled + type else enabled - type)

    /** [calendarDaysAhead] clamped to [1, MAX_CALENDAR_DAYS]. */
    val calendarWindowDays: Int get() = calendarDaysAhead.coerceIn(1, MAX_CALENDAR_DAYS)

    companion object {
        const val DEFAULT_CALENDAR_DAYS: Int = 7
        const val MAX_CALENDAR_DAYS: Int = 60
        const val SAMSUNG_REMINDER_PACKAGE: String = "com.samsung.android.app.reminder"

        val DEFAULT_ENABLED: Set<SourceType> = setOf(
            SourceType.CALENDAR,
            SourceType.BIRTHDAY,
            SourceType.HABIT,
            SourceType.GOOGLE_TASKS,
            SourceType.MS_TODO,
        )
        val DEFAULT_SAMSUNG_PACKAGES: Set<String> = setOf(SAMSUNG_REMINDER_PACKAGE)
    }
}
