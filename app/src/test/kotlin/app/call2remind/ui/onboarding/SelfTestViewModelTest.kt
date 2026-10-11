package app.call2remind.ui.onboarding

import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.awaitUntil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SelfTestViewModelTest {
    private val h = EngineHarness()
    private val vm = SelfTestViewModel(h.engine, h.occurrences, h.clock)
    private val fireAt = T0.plus(SelfTestViewModel.LEAD)
    private val occurrenceId = Occurrence.scheduled(SelfTestViewModel.testReminder("Test call", fireAt), fireAt).id

    @After
    fun tearDown() = h.close()

    private fun startAndAwait() {
        vm.start("Test call")
        awaitUntil(message = "scheduled") { vm.state.value.phase == SelfTestPhase.SCHEDULED }
    }

    @Test
    fun startSchedulesARealRingInOneMinuteThroughTheEngine() {
        startAndAwait()

        assertThat(vm.state.value.fireAt).isEqualTo(fireAt)
        assertThat(vm.state.value.remaining).isEqualTo(Duration.ofMinutes(1))
        val reminder = runBlocking { h.reminders.get(SelfTestViewModel.REMINDER_ID) }
        assertThat(reminder?.title).isEqualTo("Test call")
        assertThat(reminder?.schedule).isEqualTo(Schedule.At(fireAt))
        assertThat(runBlocking { h.occurrences.get(occurrenceId) }?.state).isEqualTo(OccurrenceState.SCHEDULED)
        awaitUntil(message = "alarm armed") { h.alarms.armed[occurrenceId]?.at == fireAt }
    }

    @Test
    fun ringingMovesToRang() {
        startAndAwait()
        h.clock.now = fireAt

        runBlocking { check(h.occurrences.tryClaimRing(occurrenceId, fireAt)) }

        awaitUntil(message = "rang") { vm.state.value.phase == SelfTestPhase.RANG }
    }

    @Test
    fun cancelDeletesThePendingTestCall() {
        startAndAwait()

        vm.cancel()

        assertThat(vm.state.value.phase).isEqualTo(SelfTestPhase.IDLE)
        awaitUntil(message = "reminder deleted") { runBlocking { h.reminders.get(SelfTestViewModel.REMINDER_ID) } == null }
        awaitUntil(message = "occurrence gone") { runBlocking { h.occurrences.getPending() }.none { it.id == occurrenceId } }
    }

    @Test
    fun leavingAfterAnAnsweredTestKeepsItsHistory() {
        startAndAwait()
        h.clock.now = fireAt
        runBlocking {
            check(h.occurrences.tryClaimRing(occurrenceId, fireAt))
            h.engine.handle(occurrenceId, OccurrenceEvent.Answer)
            h.engine.handle(occurrenceId, OccurrenceEvent.Done)
        }
        awaitUntil(message = "rang") { vm.state.value.phase == SelfTestPhase.RANG }

        vm.onLeave()
        awaitUntil { true }

        assertThat(runBlocking { h.reminders.get(SelfTestViewModel.REMINDER_ID) }).isNotNull()
        assertThat(runBlocking { h.occurrences.get(occurrenceId) }?.state).isEqualTo(OccurrenceState.DONE)
    }
}
