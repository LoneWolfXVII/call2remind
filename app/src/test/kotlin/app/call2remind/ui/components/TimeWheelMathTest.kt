package app.call2remind.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalTime

class TimeWheelMathTest {
    @Test
    fun twelveHourWheelsRoundTrip() {
        for (hour in 0..23) {
            for (minute in listOf(0, 7, 59)) {
                val time = LocalTime.of(hour, minute)
                val back = TimeWheelMath.compose(
                    TimeWheelMath.hourIndex(time, is24Hour = false),
                    minute,
                    TimeWheelMath.meridiemIndex(time),
                    is24Hour = false,
                )
                assertThat(back).isEqualTo(time)
            }
        }
    }

    @Test
    fun twentyFourHourWheelsRoundTrip() {
        val time = LocalTime.of(21, 45)
        assertThat(TimeWheelMath.compose(TimeWheelMath.hourIndex(time, true), 45, 0, true)).isEqualTo(time)
    }

    @Test
    fun labels() {
        assertThat(TimeWheelMath.hourLabel(0, is24Hour = false)).isEqualTo("12")
        assertThat(TimeWheelMath.hourLabel(9, is24Hour = false)).isEqualTo("9")
        assertThat(TimeWheelMath.hourLabel(9, is24Hour = true)).isEqualTo("09")
    }

    @Test
    fun noonAndMidnight() {
        assertThat(TimeWheelMath.compose(0, 0, 1, false)).isEqualTo(LocalTime.NOON)
        assertThat(TimeWheelMath.compose(0, 0, 0, false)).isEqualTo(LocalTime.MIDNIGHT)
    }
}
