package app.call2remind.data.json

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.recurrence.RecurrenceEnd
import app.call2remind.core.recurrence.RecurrenceRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * JSON persistence for core [Schedule]s. Core classes are not annotated; these DTOs are the
 * stable storage format. All `java.time` values use their ISO-8601 `toString()`/`parse()` forms,
 * which are lossless (nanosecond precision).
 *
 * Example: `{"type":"date","date":"2026-10-02","time":null,"lead":{"days":0,"duration":"PT0S"}}`.
 */
object ScheduleJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    /** Serializes [schedule] to JSON. */
    fun encode(schedule: Schedule): String = json.encodeToString(serializer<ScheduleDto>(), schedule.toDto())

    /**
     * Parses JSON produced by [encode]. Throws [kotlinx.serialization.SerializationException],
     * [IllegalArgumentException] or [java.time.DateTimeException] on malformed input.
     */
    fun decode(text: String): Schedule = json.decodeFromString(serializer<ScheduleDto>(), text).toModel()
}

@Serializable
internal sealed interface ScheduleDto {
    @Serializable
    @SerialName("at")
    data class At(val instant: String, val lead: LeadOffsetDto = LeadOffsetDto()) : ScheduleDto

    @Serializable
    @SerialName("date")
    data class DateOnly(val date: String, val time: String? = null, val lead: LeadOffsetDto = LeadOffsetDto()) : ScheduleDto

    @Serializable
    @SerialName("recurring")
    data class Recurring(val rule: RecurrenceRuleDto, val lead: LeadOffsetDto = LeadOffsetDto()) : ScheduleDto

    @Serializable
    @SerialName("annual")
    data class Annual(
        val monthDay: String,
        val sinceYear: Int? = null,
        val time: String? = null,
        val lead: LeadOffsetDto = LeadOffsetDto(),
    ) : ScheduleDto
}

@Serializable
internal data class LeadOffsetDto(val days: Int = 0, val duration: String = "PT0S")

@Serializable
internal data class RecurrenceRuleDto(
    val daysOfWeek: List<String>,
    val times: List<String>,
    val startDate: String,
    val intervalWeeks: Int = 1,
    val end: RecurrenceEndDto = RecurrenceEndDto.Never,
)

@Serializable
internal sealed interface RecurrenceEndDto {
    @Serializable
    @SerialName("never")
    data object Never : RecurrenceEndDto

    @Serializable
    @SerialName("until")
    data class Until(val date: String) : RecurrenceEndDto

    @Serializable
    @SerialName("count")
    data class Count(val count: Int) : RecurrenceEndDto
}

internal fun Schedule.toDto(): ScheduleDto = when (this) {
    is Schedule.At -> ScheduleDto.At(instant = instant.toString(), lead = lead.toDto())
    is Schedule.DateOnly -> ScheduleDto.DateOnly(date = date.toString(), time = time?.toString(), lead = lead.toDto())
    is Schedule.Recurring -> ScheduleDto.Recurring(rule = rule.toDto(), lead = lead.toDto())
    is Schedule.Annual -> ScheduleDto.Annual(
        monthDay = monthDay.toString(),
        sinceYear = sinceYear,
        time = time?.toString(),
        lead = lead.toDto(),
    )
}

internal fun ScheduleDto.toModel(): Schedule = when (this) {
    is ScheduleDto.At -> Schedule.At(instant = Instant.parse(instant), lead = lead.toModel())
    is ScheduleDto.DateOnly -> Schedule.DateOnly(
        date = LocalDate.parse(date),
        time = time?.let(LocalTime::parse),
        lead = lead.toModel(),
    )
    is ScheduleDto.Recurring -> Schedule.Recurring(rule = rule.toModel(), lead = lead.toModel())
    is ScheduleDto.Annual -> Schedule.Annual(
        monthDay = MonthDay.parse(monthDay),
        sinceYear = sinceYear,
        time = time?.let(LocalTime::parse),
        lead = lead.toModel(),
    )
}

private fun LeadOffset.toDto(): LeadOffsetDto = LeadOffsetDto(days = days, duration = duration.toString())

private fun LeadOffsetDto.toModel(): LeadOffset = LeadOffset(days = days, duration = Duration.parse(duration))

private fun RecurrenceRule.toDto(): RecurrenceRuleDto = RecurrenceRuleDto(
    daysOfWeek = daysOfWeek.sorted().map { it.name },
    times = times.sorted().map { it.toString() },
    startDate = startDate.toString(),
    intervalWeeks = intervalWeeks,
    end = when (val e = end) {
        RecurrenceEnd.Never -> RecurrenceEndDto.Never
        is RecurrenceEnd.Until -> RecurrenceEndDto.Until(e.date.toString())
        is RecurrenceEnd.Count -> RecurrenceEndDto.Count(e.count)
    },
)

private fun RecurrenceRuleDto.toModel(): RecurrenceRule = RecurrenceRule(
    daysOfWeek = daysOfWeek.mapTo(LinkedHashSet()) { DayOfWeek.valueOf(it) },
    times = times.mapTo(LinkedHashSet()) { LocalTime.parse(it) },
    startDate = LocalDate.parse(startDate),
    intervalWeeks = intervalWeeks,
    end = when (val e = end) {
        RecurrenceEndDto.Never -> RecurrenceEnd.Never
        is RecurrenceEndDto.Until -> RecurrenceEnd.Until(LocalDate.parse(e.date))
        is RecurrenceEndDto.Count -> RecurrenceEnd.Count(e.count)
    },
)
