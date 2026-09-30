package app.call2remind.ringing

import androidx.lifecycle.SavedStateHandle
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeTtsPlayer
import app.call2remind.testing.T0
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.minutes
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingCallViewModelTest {
    private val h = EngineHarness()
    private val tts = FakeTtsPlayer()
    private val collectors = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pay = reminder("pay", Schedule.At(T0), sourceType = SourceType.GOOGLE_TASKS, title = "Pay rent", notes = "Transfer to landlord")
    private val occ = Occurrence.scheduled(pay, T0)

    @After
    fun tearDown() {
        collectors.cancel()
        h.close()
    }

    private fun ringing(): Occurrence = runBlocking {
        h.engine.upsertReminders(listOf(pay))
        check(h.occurrences.tryClaimRing(occ.id, T0))
        requireNotNull(h.occurrences.get(occ.id))
    }

    private fun viewModel(id: String?): IncomingCallViewModel {
        val vm = IncomingCallViewModel(
            SavedStateHandle(mapOf(IncomingCallActivity.EXTRA_OCCURRENCE_ID to id)),
            h.occurrences,
            h.reminders,
            h.settings,
            h.engine,
            tts,
        )
        collectors.launch { vm.state.collect { } }
        return vm
    }

    private fun stored(): Occurrence? = runBlocking { h.occurrences.get(occ.id) }

    @Test
    fun showsTheRingingReminder() {
        val ringing = ringing()
        val vm = viewModel(occ.id)

        awaitUntil { !vm.state.value.loading }

        assertThat(vm.state.value).isEqualTo(
            CallUiState(
                loading = false,
                occurrenceId = occ.id,
                title = "Pay rent",
                notes = "Transfer to landlord",
                sourceType = SourceType.GOOGLE_TASKS,
                fireAt = ringing.fireAt,
                answered = false,
                finished = false,
            ),
        )
    }

    @Test
    fun answerThenDoneFinishesTheCall() {
        ringing()
        val vm = viewModel(occ.id)
        awaitUntil { !vm.state.value.loading }

        vm.answer()
        awaitUntil { vm.state.value.answered }
        vm.done()

        awaitUntil { vm.state.value.finished }
        assertThat(stored()?.state).isEqualTo(OccurrenceState.DONE)
    }

    @Test
    fun declineSnoozesAndFinishes() {
        ringing()
        val vm = viewModel(occ.id)
        awaitUntil { !vm.state.value.loading }

        vm.decline()

        awaitUntil { vm.state.value.finished }
        assertThat(stored()?.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(h.alarms.armed[occ.id]?.at).isEqualTo(T0.plus(minutes(5)))
    }

    @Test
    fun snoozeUsesTheConfiguredLengthUnlessGivenOne() {
        runBlocking { h.settings.update { it.copy(snoozeLength = minutes(9)) } }
        ringing()
        val vm = viewModel(occ.id)
        awaitUntil { !vm.state.value.loading }

        vm.answer()
        awaitUntil { vm.state.value.answered }
        vm.snooze()
        awaitUntil { stored()?.state == OccurrenceState.SNOOZED }
        assertThat(stored()?.fireAt).isEqualTo(T0.plus(minutes(9)))

        h.clock.now = T0.plus(minutes(9))
        runBlocking { check(h.occurrences.tryClaimRing(occ.id, h.clock.now)) }
        awaitUntil { !vm.state.value.finished }
        vm.snooze(minutes(20))
        awaitUntil { stored()?.fireAt == T0.plus(minutes(29)) }
    }

    @Test
    fun missingOccurrenceFinishesImmediately() {
        val vm = viewModel("gone")

        awaitUntil { !vm.state.value.loading }

        assertThat(vm.state.value.finished).isTrue()
    }

    @Test
    fun withoutAnIdNothingLoadsAndActionsAreIgnored() {
        ringing()
        val vm = viewModel(null)

        vm.answer()
        vm.snooze()
        Thread.sleep(SETTLE_MS)
        awaitUntil { true }

        assertThat(vm.state.value.loading).isTrue()
        assertThat(stored()?.answeredAt).isNull()
    }

    @Test
    fun bindSwitchesToAnotherOccurrence() {
        ringing()
        val vm = viewModel("gone")
        awaitUntil { vm.state.value.finished }

        vm.bind(occ.id)

        awaitUntil { vm.state.value.occurrenceId == occ.id && !vm.state.value.finished }
        assertThat(vm.state.value.title).isEqualTo("Pay rent")
    }

    private companion object {
        const val SETTLE_MS = 100L
    }
}
