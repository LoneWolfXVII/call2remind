package app.call2remind.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class SnoozeTextTest {
    @Test
    fun subMinuteSnoozesAreShownInSecondsNeverZeroMinutes() {
        assertThat(SnoozeSpan.of(Duration.ofSeconds(30))).isEqualTo(SnoozeSpan.Seconds(30))
        assertThat(SnoozeSpan.of(Duration.ofSeconds(59))).isEqualTo(SnoozeSpan.Seconds(59))
        assertThat(SnoozeSpan.of(Duration.ofMillis(59_001))).isEqualTo(SnoozeSpan.Seconds(60))
        assertThat(SnoozeSpan.of(Duration.ofMillis(1))).isEqualTo(SnoozeSpan.Seconds(1))
        assertThat(SnoozeSpan.of(Duration.ZERO)).isEqualTo(SnoozeSpan.Seconds(1))
    }

    @Test
    fun wholeMinutesAreExactAndPartialMinutesRoundUp() {
        assertThat(SnoozeSpan.of(Duration.ofMinutes(1))).isEqualTo(SnoozeSpan.Minutes(1))
        assertThat(SnoozeSpan.of(Duration.ofMinutes(5))).isEqualTo(SnoozeSpan.Minutes(5))
        assertThat(SnoozeSpan.of(Duration.ofSeconds(90))).isEqualTo(SnoozeSpan.Minutes(2))
        assertThat(SnoozeSpan.of(Duration.ofMinutes(240))).isEqualTo(SnoozeSpan.Minutes(240))
    }
}
