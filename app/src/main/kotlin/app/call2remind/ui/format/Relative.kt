package app.call2remind.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.call2remind.R
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How long ago something happened, bucketed for display ("3 min ago"). */
sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val n: Long) : Ago

    data class Hours(val n: Long) : Ago

    data class Days(val n: Long) : Ago
}

/** How far away something is ("in 20 min", "tomorrow"). */
sealed interface Until {
    data object Now : Until

    data class Minutes(val n: Long) : Until

    data class HoursMinutes(val hours: Long, val minutes: Long) : Until

    data object Tomorrow : Until

    data class OnDay(val date: LocalDate) : Until
}

/** Pure relative-time maths (unit tested); the composables below turn it into copy. */
object Relative {
    fun ago(from: Instant, now: Instant): Ago {
        val d = Duration.between(from, now).coerceAtLeast(Duration.ZERO)
        return when {
            d < Duration.ofMinutes(1) -> Ago.JustNow
            d < Duration.ofHours(1) -> Ago.Minutes(d.toMinutes())
            d < Duration.ofDays(1) -> Ago.Hours(d.toHours())
            else -> Ago.Days(d.toDays())
        }
    }

    /**
     * Same local day: minutes (under an hour) or hours + minutes. Later days: "tomorrow" or the
     * date. Minutes round up, so a call 20 min 10 s away reads "in 21 min" and never "in 0 min".
     */
    fun until(to: Instant, now: Instant, zone: ZoneId): Until {
        val d = Duration.between(now, to)
        if (d < Duration.ofMinutes(1)) return Until.Now
        val today = now.atZone(zone).toLocalDate()
        val day = to.atZone(zone).toLocalDate()
        val minutes = (d.seconds + 59) / 60
        return when {
            day == today && minutes < 60 -> Until.Minutes(minutes)
            day == today -> Until.HoursMinutes(minutes / 60, minutes % 60)
            day == today.plusDays(1) -> if (minutes < 60) Until.Minutes(minutes) else Until.Tomorrow
            else -> Until.OnDay(day)
        }
    }
}

/** Short weekday + day + month: "Thu 2 Oct". */
fun dayMonth(date: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)

/** One-letter-ish weekday label for chips ("M", "T"). */
fun weekdayLetter(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.NARROW, locale)

/** Short weekday name ("Mon"). */
fun weekdayShort(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.SHORT, locale)

/** Full weekday name ("Monday"), for accessibility. */
fun weekdayFull(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.FULL, locale)

/** Days of the week in the locale's order (Monday or Sunday first). */
fun localeWeek(locale: Locale): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

/** A wall-clock time in the device's 12/24 h format. */
@Composable
fun localTimeText(time: LocalTime): String {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return app.call2remind.ui.components.formatLocalTime(time, is24, currentLocale())
}

@Composable
fun agoText(ago: Ago): String = when (ago) {
    Ago.JustNow -> stringResource(R.string.ago_just_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.ago_minutes, ago.n.toInt(), ago.n)
    is Ago.Hours -> pluralStringResource(R.plurals.ago_hours, ago.n.toInt(), ago.n)
    is Ago.Days -> pluralStringResource(R.plurals.ago_days, ago.n.toInt(), ago.n)
}

/** The span of an [Until] ("in 20 min", "tomorrow"). */
@Composable
fun untilText(until: Until): String = when (until) {
    Until.Now -> stringResource(R.string.until_now)
    is Until.Minutes -> pluralStringResource(R.plurals.until_minutes, until.n.toInt(), until.n)
    is Until.HoursMinutes ->
        if (until.minutes == 0L) {
            pluralStringResource(R.plurals.until_hours, until.hours.toInt(), until.hours)
        } else {
            stringResource(R.string.until_hours_minutes, until.hours, until.minutes)
        }
    Until.Tomorrow -> stringResource(R.string.until_tomorrow)
    is Until.OnDay -> stringResource(R.string.until_on_day, dayMonth(until.date, currentLocale()))
}

/**
 * The current instant, re-read at the top of every minute (and immediately when [clock] changes),
 * for countdowns and "x min ago" labels. One ticker per screen.
 */
@Composable
fun rememberMinuteTicker(clock: Clock = Clock.systemDefaultZone()): State<Instant> {
    val state = remember(clock) { mutableStateOf(clock.instant()) }
    LaunchedEffect(clock) {
        while (true) {
            val now = clock.instant()
            state.value = now
            val next = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1))
            delay(Duration.between(now, next).toMillis().coerceAtLeast(MIN_TICK_MS))
        }
    }
    return state
}

private const val MIN_TICK_MS = 250L
