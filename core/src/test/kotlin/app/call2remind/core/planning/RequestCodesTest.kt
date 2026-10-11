package app.call2remind.core.planning

import app.call2remind.core.model.OccurrenceKey
import app.call2remind.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RequestCodesTest {

    @Test
    fun fnv1aMatchesPublishedVectors() {
        assertThat(RequestCodes.fnv1a32("")).isEqualTo(0x811C9DC5u)
        assertThat(RequestCodes.fnv1a32("a")).isEqualTo(0xE40C292Cu)
        assertThat(RequestCodes.fnv1a32("foobar")).isEqualTo(0xBF9CF968u)
    }

    @Test
    fun fixedVectorsNeverChange() {
        // If any of these change, alarms scheduled by an older app version become uncancellable.
        assertThat(RequestCodes.forOccurrence("")).isEqualTo(18_652_613)
        assertThat(RequestCodes.forOccurrence("a")).isEqualTo(1_678_518_572)
        assertThat(RequestCodes.forOccurrence("foobar")).isEqualTo(1_067_252_072)
        assertThat(RequestCodes.forOccurrence("CALENDAR|1767225600000|evt-1")).isEqualTo(785_526_232)
        assertThat(RequestCodes.forOccurrence("HABIT|0|h")).isEqualTo(213_953_989)
    }

    @Test
    fun nonAsciiIsHashedAsUtf8() {
        // "é" is 0xC3 0xA9 in UTF-8; hashing chars instead of bytes would give a different value.
        var expected = 0x811C9DC5u
        for (b in listOf(0xC3u, 0xA9u)) {
            expected = (expected xor b) * 0x01000193u
        }
        assertThat(RequestCodes.fnv1a32("é")).isEqualTo(expected)
    }

    @Test
    fun codesAreNonNegativeAndDeterministic() {
        val ids = sampleIds()

        ids.forEach { id ->
            val code = RequestCodes.forOccurrence(id)
            assertThat(code).isAtLeast(0)
            assertThat(RequestCodes.forOccurrence(id)).isEqualTo(code)
        }
    }

    @Test
    fun lowCollisionRateOnRealisticIds() {
        val ids = sampleIds()

        val codes = ids.map { RequestCodes.forOccurrence(it) }.toSet()

        assertThat(ids).hasSize(20_000)
        // Birthday bound for 20k ids in 2^31 codes is ~0.09 expected collisions.
        assertThat(ids.size - codes.size).isAtMost(1)
    }

    @Test
    fun dataUriIsUniquePerIdAndSafelyEncoded() {
        assertThat(RequestCodes.dataUri("HABIT|0|h")).isEqualTo("c2r://occ/HABIT%7C0%7Ch")
        assertThat(RequestCodes.dataUri("CAL|1|a/b#c?d e")).isEqualTo("c2r://occ/CAL%7C1%7Ca%2Fb%23c%3Fd%20e")
        assertThat(RequestCodes.dataUri("x-._~Z9")).isEqualTo("c2r://occ/x-._~Z9")
        assertThat(RequestCodes.dataUri("é")).isEqualTo("c2r://occ/%C3%A9")

        val ids = sampleIds() + listOf("a%7Cb", "a|b", "a b", "a+b")
        assertThat(ids.map { RequestCodes.dataUri(it) }.toSet()).hasSize(ids.toSet().size)
    }

    private fun sampleIds(): List<String> {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        return (0 until 20_000).map { i ->
            val source = SourceType.entries[i % SourceType.entries.size]
            OccurrenceKey(source, "ext-${i / 7}", start.plus(Duration.ofMinutes(15L * i))).id
        }
    }
}
