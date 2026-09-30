package app.call2remind.core.model

/**
 * Where a [Reminder] comes from.
 *
 * @property ringPriority tie-breaker used by [app.call2remind.core.ringing.RingQueue] when several
 * occurrences are due at the same instant. Lower rings first: time-critical meetings beat tasks,
 * tasks beat habits, and birthdays (never urgent) ring last.
 */
enum class SourceType(val ringPriority: Int) {
    /** Calendar events from `CalendarContract.Instances`. */
    CALENDAR(0),

    /** Samsung Reminders captured by the notification listener. */
    SAMSUNG_REMINDER(1),

    /** Microsoft To Do tasks via Microsoft Graph. */
    MS_TODO(2),

    /** Google Tasks (date-only due dates). */
    GOOGLE_TASKS(3),

    /** In-app recurring habits. */
    HABIT(4),

    /** Contact birthdays. */
    BIRTHDAY(5),
}
