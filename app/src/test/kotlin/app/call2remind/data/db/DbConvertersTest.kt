package app.call2remind.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class DbConvertersTest {

    private val converters = DbConverters()

    @Test
    fun instantsRoundTripLosslesslyIncludingNanos() {
        val instants = listOf(
            Instant.EPOCH,
            Instant.parse("2026-03-10T08:00:00Z"),
            Instant.parse("2026-03-10T08:00:00.123456789Z"),
            Instant.parse("1969-12-31T23:59:59.999999999Z"),
            Instant.parse("1700-01-01T00:00:00.000000001Z"),
            Instant.parse("2262-01-01T00:00:00Z"),
        )
        for (instant in instants) {
            assertThat(converters.epochNanosToInstant(converters.instantToEpochNanos(instant))).isEqualTo(instant)
        }
    }

    @Test
    fun epochNanosAreOrderedLikeInstants() {
        val earlier = Instant.parse("2026-03-10T08:00:00.000000001Z")
        val later = Instant.parse("2026-03-10T08:00:00.000000002Z")
        assertThat(EpochNanos.fromInstant(earlier)).isLessThan(EpochNanos.fromInstant(later))
        assertThat(EpochNanos.fromInstant(Instant.parse("1969-12-31T23:59:59.999999999Z"))).isEqualTo(-1L)
    }

    @Test
    fun outOfRangeInstantsFailLoudly() {
        assertThrows(ArithmeticException::class.java) { EpochNanos.fromInstant(Instant.parse("2300-01-01T00:00:00Z")) }
    }
}
