package app.call2remind.core.time

import app.call2remind.core.Fixtures.time
import app.call2remind.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultTimesTest {

    @Test
    fun defaultsAreNineAmForEverySource() {
        SourceType.entries.forEach { type ->
            assertThat(DefaultTimes.DEFAULT[type]).isEqualTo(time("09:00"))
        }
    }

    @Test
    fun withOverridesOnlyThatSourceAndLeavesOriginalUntouched() {
        val original = DefaultTimes.DEFAULT
        val updated = original.with(SourceType.BIRTHDAY, time("07:15"))

        assertThat(updated.forSource(SourceType.BIRTHDAY)).isEqualTo(time("07:15"))
        SourceType.entries.filter { it != SourceType.BIRTHDAY }.forEach { type ->
            assertThat(updated[type]).isEqualTo(time("09:00"))
        }
        assertThat(original.birthday).isEqualTo(time("09:00"))
    }

    @Test
    fun withCoversEverySource() {
        SourceType.entries.forEach { type ->
            assertThat(DefaultTimes.DEFAULT.with(type, time("06:45"))[type]).isEqualTo(time("06:45"))
        }
    }

    @Test
    fun copyAndMapRoundTrip() {
        val custom = DefaultTimes().copy(googleTasks = time("08:00"), calendarAllDay = time("10:30"))

        assertThat(DefaultTimes.of(custom.toMap())).isEqualTo(custom)
        assertThat(custom.toMap()).hasSize(SourceType.entries.size)
    }

    @Test
    fun ofWithPartialMapKeepsDefaults() {
        val times = DefaultTimes.of(mapOf(SourceType.MS_TODO to time("18:00")))

        assertThat(times.msTodo).isEqualTo(time("18:00"))
        assertThat(times.googleTasks).isEqualTo(time("09:00"))
    }
}
