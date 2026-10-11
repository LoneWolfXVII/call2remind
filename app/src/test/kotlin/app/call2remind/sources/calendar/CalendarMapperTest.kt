package app.call2remind.sources.calendar

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.settings.SourceSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarMapperTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val work = CalendarInfo(id = 1, displayName = "Work", accountName = "me@example.com", accountType = "com.google")
    private val settings = SourceSettings()

    private fun instance(
        eventId: Long = 10,
        begin: String = "2026-03-10T09:30:00Z",
        allDay: Boolean = false,
        calendarId: Long = 1,
        title: String? = "Standup",
        status: Int? = null,
        self: Int? = null,
    ) = CalendarInstance(
        eventId = eventId,
        calendarId = calendarId,
        begin = Instant.parse(begin),
        end = null,
        title = title,
        allDay = allDay,
        status = status,
        selfAttendeeStatus = self,
    )

    private fun map(
        instances: List<CalendarInstance>,
        calendars: List<CalendarInfo> = listOf(work),
        reminders: Map<Long, List<CalendarReminder>> = emptyMap(),
        settings: SourceSettings = this.settings,
        zone: ZoneId = kolkata,
    ) = CalendarMapper.map(instances, calendars, reminders, settings, zone)

    @Test
    fun timedEventRingsAtStartWithIdsFromEventAndInstance() {
        val result = map(listOf(instance()))

        val reminder = result.single()
        assertThat(reminder.externalId).isEqualTo("10:${Instant.parse("2026-03-10T09:30:00Z").toEpochMilli()}")
        assertThat(reminder.id).isEqualTo(ReminderIds.of(SourceType.CALENDAR, reminder.externalId))
        assertThat(reminder.sourceType).isEqualTo(SourceType.CALENDAR)
        assertThat(reminder.title).isEqualTo("Standup")
        assertThat(reminder.schedule).isEqualTo(Schedule.At(Instant.parse("2026-03-10T09:30:00Z")))
        assertThat(reminder.zone).isEqualTo(kolkata)
    }

    @Test
    fun recurringInstancesBecomeSeparateRemindersKeyedByInstanceBegin() {
        val result = map(
            listOf(
                instance(begin = "2026-03-10T09:30:00Z"),
                instance(begin = "2026-03-11T09:30:00Z"),
                instance(begin = "2026-03-12T09:30:00Z"),
            ),
        )

        assertThat(result.map { it.externalId }).containsExactly(
            "10:${Instant.parse("2026-03-10T09:30:00Z").toEpochMilli()}",
            "10:${Instant.parse("2026-03-11T09:30:00Z").toEpochMilli()}",
            "10:${Instant.parse("2026-03-12T09:30:00Z").toEpochMilli()}",
        ).inOrder()
        assertThat(result.map { it.id }.toSet()).hasSize(3)
    }

    @Test
    fun duplicateInstanceRowsAreCollapsed() {
        assertThat(map(listOf(instance(), instance()))).hasSize(1)
    }

    @Test
    fun smallestAlertReminderBecomesTheLead() {
        val reminders = mapOf(
            10L to listOf(
                CalendarReminder(30, CalendarConstants.METHOD_ALERT),
                CalendarReminder(10, CalendarConstants.METHOD_DEFAULT),
                CalendarReminder(60, CalendarConstants.METHOD_ALARM),
            ),
        )

        val schedule = map(listOf(instance()), reminders = reminders).single().schedule

        assertThat(schedule.lead).isEqualTo(LeadOffset.minutes(10))
    }

    @Test
    fun emailSmsAndNegativeRemindersAreIgnored() {
        val reminders = mapOf(
            10L to listOf(
                CalendarReminder(5, CalendarConstants.METHOD_EMAIL),
                CalendarReminder(1, CalendarConstants.METHOD_SMS),
                CalendarReminder(-1, CalendarConstants.METHOD_ALERT),
                CalendarReminder(15, CalendarConstants.METHOD_ALERT),
            ),
        )

        assertThat(map(listOf(instance()), reminders = reminders).single().schedule.lead).isEqualTo(LeadOffset.minutes(15))
    }

    @Test
    fun noUsableReminderRingsAtStart() {
        assertThat(CalendarMapper.leadFor(emptyList())).isEqualTo(LeadOffset.NONE)
        assertThat(CalendarMapper.leadFor(listOf(CalendarReminder(0, CalendarConstants.METHOD_ALERT)))).isEqualTo(LeadOffset.NONE)
        assertThat(CalendarMapper.leadFor(listOf(CalendarReminder(10, CalendarConstants.METHOD_EMAIL)))).isEqualTo(LeadOffset.NONE)
    }

    @Test
    fun remindersOfOtherEventsDoNotLeak() {
        val reminders = mapOf(99L to listOf(CalendarReminder(10, CalendarConstants.METHOD_ALERT)))

        assertThat(map(listOf(instance()), reminders = reminders).single().schedule.lead).isEqualTo(LeadOffset.NONE)
    }

    @Test
    fun allDayEventUsesTheUtcDateEastOfGreenwich() {
        // All-day instants are UTC midnight: 2026-03-15T00:00Z is 05:30 on the 15th in Kolkata.
        val result = map(listOf(instance(begin = "2026-03-15T00:00:00Z", allDay = true)), zone = kolkata)

        assertThat(result.single().schedule).isEqualTo(Schedule.DateOnly(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun allDayEventUsesTheUtcDateWestOfGreenwich() {
        // In Los Angeles the same instant is still the 14th locally; the event is on the 15th.
        val result = map(listOf(instance(begin = "2026-03-15T00:00:00Z", allDay = true)), zone = losAngeles)

        val reminder = result.single()
        assertThat(reminder.schedule).isEqualTo(Schedule.DateOnly(LocalDate.of(2026, 3, 15)))
        assertThat(reminder.zone).isEqualTo(losAngeles)
    }

    @Test
    fun allDayEventIgnoresReminderOffsetsAndUsesTheDefaultTime() {
        val reminders = mapOf(10L to listOf(CalendarReminder(15, CalendarConstants.METHOD_ALERT)))

        val schedule = map(listOf(instance(begin = "2026-03-15T00:00:00Z", allDay = true)), reminders = reminders).single().schedule

        assertThat(schedule).isEqualTo(Schedule.DateOnly(LocalDate.of(2026, 3, 15)))
        assertThat((schedule as Schedule.DateOnly).time).isNull()
    }

    @Test
    fun declinedAndCancelledEventsAreSkipped() {
        val result = map(
            listOf(
                instance(eventId = 1, self = CalendarConstants.ATTENDEE_STATUS_DECLINED),
                instance(eventId = 2, status = CalendarConstants.STATUS_CANCELED),
                instance(eventId = 3, self = 1, status = 1),
            ),
        )

        assertThat(result.map { it.externalId.substringBefore(':') }).containsExactly("3")
    }

    @Test
    fun hiddenUnsyncedUnknownAndExcludedCalendarsAreSkipped() {
        val calendars = listOf(
            work,
            work.copy(id = 2, visible = false),
            work.copy(id = 3, syncEvents = false),
            work.copy(id = 4),
        )
        val instances = (1L..5L).map { instance(eventId = it, calendarId = it) }

        val result = map(instances, calendars = calendars, settings = settings.copy(excludedCalendarIds = setOf(4L)))

        assertThat(result.map { it.externalId.substringBefore(':') }).containsExactly("1")
    }

    @Test
    fun contactBirthdaysCalendarIsSkippedOnlyWhileTheBirthdaySourceIsOn() {
        val birthdays = CalendarInfo(
            id = 7,
            displayName = "Birthdays",
            accountName = "me@example.com",
            accountType = "com.google",
            ownerAccount = "addressbook#contacts@group.v.calendar.google.com",
        )
        val instances = listOf(instance(eventId = 70, calendarId = 7, allDay = true, begin = "2026-03-15T00:00:00Z"))

        assertThat(map(instances, calendars = listOf(birthdays))).isEmpty()
        val withoutBirthdaySource = settings.withEnabled(SourceType.BIRTHDAY, false)
        assertThat(map(instances, calendars = listOf(birthdays), settings = withoutBirthdaySource)).hasSize(1)
    }

    @Test
    fun blankTitlesGetAPlaceholder() {
        val result = map(listOf(instance(eventId = 1, title = null), instance(eventId = 2, title = "  ")))

        assertThat(result.map { it.title }).containsExactly(CalendarMapper.UNTITLED, CalendarMapper.UNTITLED)
    }

    @Test
    fun titlesAreTrimmed() {
        assertThat(map(listOf(instance(title = "  Dentist \n"))).single().title).isEqualTo("Dentist")
    }
}
