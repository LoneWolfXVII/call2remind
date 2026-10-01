package app.call2remind.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.call2remind.R
import java.time.Duration

/**
 * A snooze length as shown to the user. `Duration.toMinutes()` truncates, so a sub-minute snooze
 * (possible from settings written by tests or older builds) read "Snooze 0 min". Below a minute
 * the span is shown in seconds; from a minute on, partial minutes round up, so a label never
 * promises a shorter wait than the real one.
 */
sealed interface SnoozeSpan {
    data class Seconds(val seconds: Long) : SnoozeSpan

    data class Minutes(val minutes: Long) : SnoozeSpan

    companion object {
        fun of(length: Duration): SnoozeSpan {
            val millis = length.toMillis().coerceAtLeast(0)
            return if (millis < MINUTE_MS) {
                Seconds(ceilDiv(millis, SECOND_MS).coerceAtLeast(1))
            } else {
                Minutes(ceilDiv(millis, MINUTE_MS))
            }
        }
    }
}

private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L

/** "5 min" / "30 s". */
@Composable
fun snoozeLengthText(length: Duration): String = when (val span = SnoozeSpan.of(length)) {
    is SnoozeSpan.Seconds -> stringResource(R.string.snooze_seconds, span.seconds)
    is SnoozeSpan.Minutes -> stringResource(R.string.snooze_minutes, span.minutes)
}

/** "Snooze 5 min" / "Snooze 30 s". */
@Composable
fun snoozeButtonText(length: Duration): String = when (val span = SnoozeSpan.of(length)) {
    is SnoozeSpan.Seconds -> stringResource(R.string.call_snooze_seconds, span.seconds)
    is SnoozeSpan.Minutes -> stringResource(R.string.call_snooze_minutes, span.minutes)
}
