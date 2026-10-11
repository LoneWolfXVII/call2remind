package app.call2remind.sources.mstodo

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class GraphTimeTest {
    @Test
    fun windowsZoneNamesMapToIana() {
        assertThat(GraphTime.zone("India Standard Time")).isEqualTo(ZoneId.of("Asia/Kolkata"))
        assertThat(GraphTime.zone("Pacific Standard Time")).isEqualTo(ZoneId.of("America/Los_Angeles"))
        assertThat(GraphTime.zone("W. Europe Standard Time")).isEqualTo(ZoneId.of("Europe/Berlin"))
        assertThat(GraphTime.zone("GMT Standard Time")).isEqualTo(ZoneId.of("Europe/London"))
        assertThat(GraphTime.zone("AUS Eastern Standard Time")).isEqualTo(ZoneId.of("Australia/Sydney"))
        assertThat(GraphTime.zone("eastern standard time")).isEqualTo(ZoneId.of("America/New_York"))
    }

    @Test
    fun everyMappedZoneIsAValidIanaId() {
        listOf(
            "Argentina Standard Time", "Myanmar Standard Time", "Nepal Standard Time", "Central Standard Time (Mexico)",
            "Newfoundland Standard Time", "Cen. Australia Standard Time", "E. Europe Standard Time", "FLE Standard Time",
        ).forEach { name -> assertThat(GraphTime.zone(name)).isNotEqualTo(ZoneOffset.UTC) }
    }

    @Test
    fun utcVariantsIanaIdsAndFallbacks() {
        assertThat(GraphTime.zone("UTC")).isEqualTo(ZoneOffset.UTC)
        assertThat(GraphTime.zone("Coordinated Universal Time")).isEqualTo(ZoneOffset.UTC)
        assertThat(GraphTime.zone("Etc/UTC")).isEqualTo(ZoneOffset.UTC)
        assertThat(GraphTime.zone("Europe/Paris")).isEqualTo(ZoneId.of("Europe/Paris"))
        assertThat(GraphTime.zone("+05:30")).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30))
        assertThat(GraphTime.zone("Mars Standard Time")).isEqualTo(ZoneOffset.UTC)
        assertThat(GraphTime.zone("")).isEqualTo(ZoneOffset.UTC)
        assertThat(GraphTime.zone(null)).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun sevenDigitFractionalSecondsAreParsed() {
        assertThat(GraphTime.instant("2026-03-10T09:00:00.0000000", "UTC")).isEqualTo(Instant.parse("2026-03-10T09:00:00Z"))
        assertThat(GraphTime.instant("2026-03-10T09:00:00.1234567", "UTC")).isEqualTo(Instant.parse("2026-03-10T09:00:00.1234567Z"))
        assertThat(GraphTime.instant("2026-03-10T09:00:00", "UTC")).isEqualTo(Instant.parse("2026-03-10T09:00:00Z"))
    }

    @Test
    fun localTimeIsResolvedInTheGivenZone() {
        assertThat(GraphTime.instant("2026-03-10T09:00:00.0000000", "India Standard Time"))
            .isEqualTo(Instant.parse("2026-03-10T03:30:00Z"))
        // DST in effect in New York on 2026-07-01 (UTC-4).
        assertThat(GraphTime.instant("2026-07-01T09:00:00.0000000", "Eastern Standard Time"))
            .isEqualTo(Instant.parse("2026-07-01T13:00:00Z"))
    }

    @Test
    fun explicitOffsetWinsAndGarbageIsNull() {
        assertThat(GraphTime.instant("2026-03-10T09:00:00Z", "India Standard Time")).isEqualTo(Instant.parse("2026-03-10T09:00:00Z"))
        assertThat(GraphTime.instant("2026-03-10T09:00:00.5+01:00", null)).isEqualTo(Instant.parse("2026-03-10T08:00:00.5Z"))
        assertThat(GraphTime.instant("tomorrow", "UTC")).isNull()
        assertThat(GraphTime.instant(null, "UTC")).isNull()
        assertThat(GraphTime.instant("  ", "UTC")).isNull()
    }
}
