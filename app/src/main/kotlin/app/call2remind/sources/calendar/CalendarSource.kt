package app.call2remind.sources.calendar

import app.call2remind.core.model.SourceType
import app.call2remind.di.IoDispatcher
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.ReminderSource
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar events of every synced account, read from `CalendarContract.Instances` for
 * `[now - 1 day, now + calendarDaysAhead + 1 day)`. The extra day on each side keeps ongoing
 * events (a snoozed call must keep its reminder) and covers all-day instants, which are UTC
 * midnights. Always a full snapshot (the provider is local and cheap). Mapping: [CalendarMapper].
 */
@Singleton
class CalendarSource @Inject constructor(
    private val reader: CalendarReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReminderSource {
    override val type: SourceType = SourceType.CALENDAR
    override val requiresNetwork: Boolean = false

    override suspend fun availability(): SourceAvailability =
        if (reader.hasPermission()) SourceAvailability.Ready else SourceAvailability.NeedsPermission(READ_CALENDAR)

    override suspend fun snapshot(request: SyncRequest): SourceSnapshot = withContext(io) {
        if (!reader.hasPermission()) throw NeedsPermissionException(READ_CALENDAR)
        try {
            val begin = request.now.minus(MARGIN)
            val end = request.now.plus(Duration.ofDays(request.settings.calendarWindowDays.toLong())).plus(MARGIN)
            val instances = reader.instances(begin, end)
            val calendars = reader.calendars()
            val timedEventIds = instances.filterNot { it.allDay }.mapTo(HashSet()) { it.eventId }
            val reminders = if (timedEventIds.isEmpty()) emptyMap() else reader.reminders(timedEventIds)
            SourceSnapshot.Full(CalendarMapper.map(instances, calendars, reminders, request.settings, request.zone))
        } catch (e: SecurityException) {
            throw NeedsPermissionException(READ_CALENDAR)
        }
    }

    companion object {
        const val READ_CALENDAR: String = "android.permission.READ_CALENDAR"
        private val MARGIN: Duration = Duration.ofDays(1)
    }
}
