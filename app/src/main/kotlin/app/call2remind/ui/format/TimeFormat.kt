package app.call2remind.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A clock time split for display: the digits ("9:30") and the optional meridiem ("AM"). */
data class TimeParts(val digits: String, val meridiem: String?) {
    /** Single-line form, e.g. "9:30 AM" / "21:30". */
    val text: String get() = if (meridiem == null) digits else "$digits $meridiem"
}

/** Formatting shared by every screen; pure so it is unit-testable. */
object TimeFormat {
    fun parts(instant: Instant, zone: ZoneId, locale: Locale, is24Hour: Boolean): TimeParts {
        val time = instant.atZone(zone)
        return if (is24Hour) {
            TimeParts(DateTimeFormatter.ofPattern("H:mm", locale).format(time), null)
        } else {
            TimeParts(
                DateTimeFormatter.ofPattern("h:mm", locale).format(time),
                DateTimeFormatter.ofPattern("a", locale).format(time).uppercase(locale),
            )
        }
    }

    /** "Thu, 2 Oct". */
    fun shortDate(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(instant.atZone(zone))

    /** Countdown / elapsed "m:ss" (negative durations clamp to 0:00). */
    fun minutesSeconds(duration: Duration): String {
        val total = duration.seconds.coerceAtLeast(0)
        return "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
    }
}

/** The user's primary locale. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.getDefault()

/** [TimeFormat.parts] with the device's 12/24-hour setting, locale and zone. */
@Composable
@ReadOnlyComposable
fun timeParts(instant: Instant): TimeParts =
    TimeFormat.parts(instant, ZoneId.systemDefault(), currentLocale(), DateFormat.is24HourFormat(LocalContext.current))
