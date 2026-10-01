package app.call2remind.core.sync

import java.time.Duration
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with **full jitter** for sync retries:
 * `delay(attempt) = uniform(0 ..= min(cap, base * multiplier^attempt))`.
 *
 * @property base ceiling for the first retry (attempt 0).
 * @property cap maximum ceiling.
 * @property random injectable randomness (seed it in tests).
 */
class Backoff(
    val base: Duration = Duration.ofSeconds(30),
    val cap: Duration = Duration.ofMinutes(30),
    val multiplier: Double = 2.0,
    private val random: Random = Random.Default,
) {
    init {
        require(base > Duration.ZERO) { "base must be positive" }
        require(cap >= base) { "cap must be >= base" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    /** Upper bound of the delay for 0-based [attempt], before jitter. Never exceeds [cap]. */
    fun ceilingFor(attempt: Int): Duration {
        require(attempt >= 0) { "attempt must be >= 0, was $attempt" }
        val capMillis = cap.toMillis()
        val ceiling = base.toMillis() * multiplier.pow(attempt)
        // pow may overflow to Infinity for large attempts; anything >= cap is the cap.
        return if (ceiling >= capMillis) cap else Duration.ofMillis(ceiling.toLong())
    }

    /** Jittered delay for 0-based [attempt], uniformly in `[0, ceilingFor(attempt)]`. */
    fun delayFor(attempt: Int): Duration {
        val ceilingMillis = ceilingFor(attempt).toMillis()
        return Duration.ofMillis(random.nextLong(0, ceilingMillis + 1))
    }
}
