package app.call2remind.core.time

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import java.time.ZoneId

/**
 * Which reminders follow the device's **current** time zone ("floating" wall-clock reminders)
 * rather than the zone stored with them.
 *
 * A reminder that follows the device zone rings at the same local wall time after the phone's
 * zone changes: the scheduling engine re-maps its [Reminder.zone] to the current zone on every
 * replan (which runs on TIMEZONE_CHANGED, on app start and after every sync), so no re-sync is
 * needed. Occurrences already SNOOZED or RINGING are kept as they are by the planner.
 *
 * - [SourceType.CALENDAR], [SourceType.GOOGLE_TASKS], [SourceType.BIRTHDAY]: always. Their
 *   mappers store the device zone at sync time (all-day events, due dates and birthdays are
 *   dates, not instants), so re-mapping gives exactly what a re-sync in the new zone would.
 * - [SourceType.HABIT]: per [HABITS_FOLLOW_DEVICE_ZONE].
 * - [SourceType.MS_TODO] (explicit zone from Graph) and [SourceType.SAMSUNG_REMINDER] (one-shot
 *   instants): never.
 */
object DeviceZonePolicy {
    /**
     * **Product switch.** `true`: habits ring at the same local wall time after travel (a 07:00
     * habit set in Kolkata rings at 07:00 in Los Angeles). `false`: a habit keeps the zone it was
     * created in (07:00 Kolkata = 18:30 the day before in Los Angeles). Flip this one constant to
     * revert; nothing else depends on the choice.
     */
    const val HABITS_FOLLOW_DEVICE_ZONE: Boolean = true

    /** True if [reminder]'s wall-clock schedule is resolved in the device's current zone. */
    fun followsDeviceZone(
        reminder: Reminder,
        habitsFollow: Boolean = HABITS_FOLLOW_DEVICE_ZONE,
    ): Boolean = when (reminder.sourceType) {
        SourceType.CALENDAR, SourceType.GOOGLE_TASKS, SourceType.BIRTHDAY -> true
        SourceType.HABIT -> habitsFollow
        SourceType.MS_TODO, SourceType.SAMSUNG_REMINDER -> false
    }

    /**
     * The reminders of [reminders] that follow the device zone but are stored with another zone,
     * already moved to [deviceZone] (input order kept). Empty if nothing needs to change.
     */
    fun toDeviceZone(
        reminders: Collection<Reminder>,
        deviceZone: ZoneId,
        habitsFollow: Boolean = HABITS_FOLLOW_DEVICE_ZONE,
    ): List<Reminder> = reminders
        .filter { it.zone != deviceZone && followsDeviceZone(it, habitsFollow) }
        .map { it.copy(zone = deviceZone) }
}
