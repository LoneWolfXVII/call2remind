package app.call2remind.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import kotlin.random.Random

class BackoffTest {

    /** Always returns the lowest or highest allowed value. */
    private class ExtremeRandom(private val pickMax: Boolean) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(from: Long, until: Long): Long = if (pickMax) until - 1 else from
    }

    private val backoff = Backoff(base = Duration.ofSeconds(30), cap = Duration.ofMinutes(30))

    @Test
    fun ceilingGrowsExponentiallyUpToCap() {
        assertThat(backoff.ceilingFor(0)).isEqualTo(Duration.ofSeconds(30))
        assertThat(backoff.ceilingFor(1)).isEqualTo(Duration.ofSeconds(60))
        assertThat(backoff.ceilingFor(2)).isEqualTo(Duration.ofSeconds(120))
        assertThat(backoff.ceilingFor(5)).isEqualTo(Duration.ofSeconds(960))
        assertThat(backoff.ceilingFor(6)).isEqualTo(Duration.ofMinutes(30))
        assertThat(backoff.ceilingFor(1_000)).isEqualTo(Duration.ofMinutes(30))
        assertThat(backoff.ceilingFor(Int.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun multiplierOfOneIsConstant() {
        val flat = Backoff(base = Duration.ofSeconds(10), cap = Duration.ofMinutes(1), multiplier = 1.0)

        assertThat(flat.ceilingFor(Int.MAX_VALUE)).isEqualTo(Duration.ofSeconds(10))
    }

    @Test
    fun fullJitterSpansZeroToCeilingInclusive() {
        val low = Backoff(random = ExtremeRandom(pickMax = false))
        val high = Backoff(random = ExtremeRandom(pickMax = true))

        assertThat(low.delayFor(3)).isEqualTo(Duration.ZERO)
        assertThat(high.delayFor(3)).isEqualTo(low.ceilingFor(3))
        assertThat(high.delayFor(50)).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun seededRandomIsDeterministicAndInRange() {
        val a = Backoff(random = Random(42))
        val b = Backoff(random = Random(42))

        val delaysA = (0 until 12).map { a.delayFor(it) }
        val delaysB = (0 until 12).map { b.delayFor(it) }

        assertThat(delaysA).isEqualTo(delaysB)
        delaysA.forEachIndexed { attempt, delay ->
            assertThat(delay).isAtLeast(Duration.ZERO)
            assertThat(delay).isAtMost(a.ceilingFor(attempt))
        }
    }

    @Test
    fun validation() {
        assertThrows(IllegalArgumentException::class.java) { backoff.ceilingFor(-1) }
        assertThrows(IllegalArgumentException::class.java) { Backoff(base = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) {
            Backoff(base = Duration.ofMinutes(5), cap = Duration.ofMinutes(1))
        }
        assertThrows(IllegalArgumentException::class.java) { Backoff(multiplier = 0.5) }
    }
}
