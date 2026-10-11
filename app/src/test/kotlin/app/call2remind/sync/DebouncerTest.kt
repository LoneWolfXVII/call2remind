package app.call2remind.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {
    @Test
    fun burstIsCollapsedIntoOneRunAfterTheQuietPeriod() = runTest {
        val runs = mutableListOf<Set<String>>()
        val debouncer = Debouncer<String>(this, Duration.ofSeconds(2)) { runs += it }

        debouncer.submit("a")
        advanceTimeBy(1_500)
        debouncer.submit("b")
        advanceTimeBy(1_500)
        debouncer.submit("a")
        advanceTimeBy(1_999)
        runCurrent()
        assertThat(runs).isEmpty()

        advanceTimeBy(2)
        runCurrent()
        assertThat(runs).containsExactly(setOf("a", "b"))
    }

    @Test
    fun separateBurstsRunSeparately() = runTest {
        val runs = mutableListOf<Set<Int>>()
        val debouncer = Debouncer<Int>(this, Duration.ofSeconds(2)) { runs += it }

        debouncer.submit(1)
        advanceUntilIdle()
        debouncer.submit(2)
        advanceUntilIdle()

        assertThat(runs).containsExactly(setOf(1), setOf(2)).inOrder()
    }

    @Test
    fun aRunningActionIsNotCancelledByNewSubmissions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Set<Int>>()
        val finished = mutableListOf<Set<Int>>()
        val debouncer = Debouncer<Int>(this, Duration.ofSeconds(2)) { items ->
            started += items
            gate.await()
            finished += items
        }

        debouncer.submit(1)
        advanceTimeBy(2_001)
        runCurrent()
        assertThat(started).containsExactly(setOf(1))

        debouncer.submit(2)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(finished).containsExactly(setOf(1), setOf(2)).inOrder()
    }
}
