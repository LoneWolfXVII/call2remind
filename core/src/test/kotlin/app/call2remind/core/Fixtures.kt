package app.call2remind.core

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared zones and builders for core tests. */
object Fixtures {
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    val LONDON: ZoneId = ZoneId.of("Europe/London")
    val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")

    /** `Instant.parse` shortcut. */
    fun instant(text: String): Instant = Instant.parse(text)

    /** Instant for a wall-clock time at an explicit offset, e.g. `at("2026-03-08T03:30", -4)`. */
    fun at(localDateTime: String, offsetHours: Int): Instant =
        LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.ofHours(offsetHours))

    /** Instant for a wall-clock time at an offset with minutes, e.g. IST +05:30. */
    fun at(localDateTime: String, offsetHours: Int, offsetMinutes: Int): Instant =
        LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.ofHoursMinutes(offsetHours, offsetMinutes))

    fun date(text: String): LocalDate = LocalDate.parse(text)

    fun time(text: String): LocalTime = LocalTime.parse(text)

    fun fixedClock(now: Instant): Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun reminder(
        id: String = "r1",
        schedule: Schedule,
        sourceType: SourceType = SourceType.GOOGLE_TASKS,
        externalId: String = "ext-$id",
        zone: ZoneId = KOLKATA,
        title: String = "Pay rent",
        notes: String? = null,
        enabled: Boolean = true,
    ): Reminder = Reminder(
        id = id,
        sourceType = sourceType,
        externalId = externalId,
        title = title,
        schedule = schedule,
        zone = zone,
        notes = notes,
        enabled = enabled,
    )

    fun occurrence(
        id: String = "o1",
        fireAt: Instant,
        state: OccurrenceState = OccurrenceState.SCHEDULED,
        sourceType: SourceType = SourceType.GOOGLE_TASKS,
        ringBacks: Int = 0,
        answeredAt: Instant? = null,
        reminderId: String = "r1",
    ): Occurrence = Occurrence(
        id = id,
        reminderId = reminderId,
        sourceType = sourceType,
        plannedAt = fireAt,
        fireAt = fireAt,
        state = state,
        ringBacks = ringBacks,
        answeredAt = answeredAt,
    )
}
