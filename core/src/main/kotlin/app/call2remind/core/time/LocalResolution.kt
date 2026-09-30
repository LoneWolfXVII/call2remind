package app.call2remind.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Resolves a wall-clock [date] + [time] in [zone] to an instant, with the app-wide DST rules:
 *
 * - **Gap** (spring forward): a time that does not exist is shifted forward by the length of the
 *   gap, e.g. 02:30 on a New York spring-forward day becomes 03:30 EDT.
 * - **Overlap** (fall back): a time that exists twice uses the earlier offset (the first
 *   occurrence), so it rings once.
 *
 * These are exactly the semantics of [ZonedDateTime.of] without a preferred offset.
 */
fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
    ZonedDateTime.of(date, time, zone).toInstant()
