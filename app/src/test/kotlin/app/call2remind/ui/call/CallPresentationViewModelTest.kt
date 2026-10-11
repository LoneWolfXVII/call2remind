package app.call2remind.ui.call

import androidx.lifecycle.SavedStateHandle
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.ringing.IncomingCallActivity
import app.call2remind.ringing.TtsEvent
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.minutes
import app.call2remind.testing.reminder
import app.call2remind.ui.ScriptedTtsPlayer
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
class CallPresentationViewModelTest {
    private val h = EngineHarness()
    private val tts = ScriptedTtsPlayer()
    private val collectors = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pay = reminder("pay", Schedule.At(T0), sourceType = SourceType.GOOGLE_TASKS, title = "Pay rent", notes = "Transfer to landlord")
    private val occ = Occurrence.scheduled(pay, T0)

    @After
    fun tearDown() {
        collectors.cancel()
        h.close()
    }

    private fun ring(): Occurrence = runBlocking {
        h.engine.upsertReminders(listOf(pay))
        check(h.occurrences.tryClaimRing(occ.id, T0))
        requireNotNull(h.occurrences.get(occ.id))
    }

    private fun viewModel(): CallPresentationViewModel {
        val vm = CallPresentationViewModel(
            SavedStateHandle(mapOf(IncomingCallActivity.EXTRA_OCCURRENCE_ID to occ.id)),
            h.occurrences,
            h.reminders,
            h.settings,
            tts,
        )
        collectors.launch { vm.presentation.collect { } }
        return vm
    }

    @Test
    fun presentsTranscriptAndSnoozeAllowance() {
        ring()
        val vm = viewModel()

        awaitUntil { vm.presentation.value.transcript.isNotEmpty() }

        with(vm.presentation.value) {
            assertThat(transcript).isEqualTo("Reminder: Pay rent. Transfer to landlord.")
            assertThat(spokenText).isEqualTo(transcript)
            assertThat(canSnooze).isTrue()
            assertThat(ringBacksLeft).isEqualTo(3)
            assertThat(defaultSnooze).isEqualTo(minutes(5))
            assertThat(plannedAt).isEqualTo(T0)
        }
    }

    @Test
    fun cannotSnoozeOnceRingBacksAreUsedUp() {
        runBlocking { h.settings.update { it.copy(maxRingBacks = 0) } }
        ring()
        val vm = viewModel()

        awaitUntil { vm.presentation.value.transcript.isNotEmpty() }

        assertThat(vm.presentation.value.canSnooze).isFalse()
        assertThat(vm.presentation.value.ringBacksLeft).isEqualTo(0)
    }

    @Test
    fun voiceOffKeepsTheTranscriptButNoSpokenText() {
        runBlocking { h.settings.update { it.copy(ttsEnabled = false) } }
        ring()
        val vm = viewModel()

        awaitUntil { vm.presentation.value.transcript.isNotEmpty() }

        assertThat(vm.presentation.value.spokenText).isNull()
    }

    @Test
    fun speechProgressFollowsTtsRanges() {
        ring()
        val vm = viewModel()

        tts.emit(TtsEvent.Started("u1"))
        tts.emit(TtsEvent.Range("u1", 0, 9))
        awaitUntil { vm.speech.value.spokenUntil == 9 }
        assertThat(vm.speech.value.rangeExposed).isTrue()
        assertThat(vm.speech.value.speaking).isTrue()

        // Ranges of another utterance while this one speaks are ignored.
        tts.emit(TtsEvent.Range("other", 0, 30))
        tts.emit(TtsEvent.Range("u1", 10, 13))
        awaitUntil { vm.speech.value.spokenUntil == 13 }

        tts.emit(TtsEvent.Done("u1"))
        awaitUntil { vm.speech.value.finished }
        assertThat(vm.speech.value.speaking).isFalse()
    }

    @Test
    fun adoptsAnUtteranceThatStartedBeforeTheScreen() {
        ring()
        val vm = viewModel()

        tts.emit(TtsEvent.Range("early", 5, 12))

        awaitUntil { vm.speech.value.utteranceId == "early" }
        assertThat(vm.speech.value.spokenUntil).isEqualTo(12)
    }

    @Test
    fun readAgainSpeaksTheTranscriptAndStopVoiceStops() {
        ring()
        val vm = viewModel()
        awaitUntil { vm.presentation.value.transcript.isNotEmpty() }

        vm.readAgain()
        awaitUntil { tts.spoken.isNotEmpty() }
        assertThat(tts.spoken).containsExactly("Reminder: Pay rent. Transfer to landlord.")

        vm.stopVoice()
        assertThat(tts.stops).isEqualTo(1)
        assertThat(vm.speech.value.speaking).isFalse()
    }
}
