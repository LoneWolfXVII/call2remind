package app.call2remind.core.ringing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingDecisionTest {

    /** All 32 input combinations. */
    private val all: List<RingContext> = (0 until 32).map { bits ->
        RingContext(
            inRealCall = (bits and 1) != 0,
            dndTotalSilence = (bits and 2) != 0,
            appInForeground = (bits and 4) != 0,
            canUseFullScreenIntent = (bits and 8) != 0,
            screenInteractive = (bits and 16) != 0,
        )
    }

    private fun ctx(
        inRealCall: Boolean = false,
        dnd: Boolean = false,
        foreground: Boolean = false,
        fsi: Boolean = true,
        interactive: Boolean = false,
    ) = RingContext(inRealCall, dnd, foreground, fsi, interactive)

    @Test
    fun normalLockedPhoneRingsFullScreen() {
        assertThat(RingDecision.decide(ctx())).isEqualTo(RingMode.FULL_SCREEN)
        assertThat(RingDecision.decide(ctx(interactive = true))).isEqualTo(RingMode.FULL_SCREEN)
    }

    @Test
    fun realCallAlwaysDefers() {
        all.filter { it.inRealCall }.forEach {
            assertThat(RingDecision.decide(it)).isEqualTo(RingMode.DEFER_UNTIL_CALL_ENDS)
            assertThat(RingDecision.soundAllowed(it)).isFalse()
            assertThat(RingDecision.vibrationAllowed(it)).isFalse()
        }
    }

    @Test
    fun visibleAppUsesOverlayWhenNotInCall() {
        all.filter { !it.inRealCall && it.appInForeground && it.screenInteractive }.forEach {
            assertThat(RingDecision.decide(it)).isEqualTo(RingMode.IN_APP_OVERLAY)
        }
    }

    @Test
    fun foregroundWithScreenOffIsTreatedAsBackground() {
        assertThat(RingDecision.decide(ctx(foreground = true, interactive = false))).isEqualTo(RingMode.FULL_SCREEN)
    }

    @Test
    fun withoutFullScreenIntentDegradesToHeadsUp() {
        all.filter { !it.inRealCall && !(it.appInForeground && it.screenInteractive) && !it.canUseFullScreenIntent }
            .forEach { assertThat(RingDecision.decide(it)).isEqualTo(RingMode.HEADS_UP_DEGRADED) }
    }

    @Test
    fun dndTotalSilenceIsASilentNotificationWithoutSoundOrVibration() {
        val context = ctx(dnd = true)

        assertThat(RingDecision.decide(context)).isEqualTo(RingMode.DND_SILENT_NOTIFICATION)
        assertThat(RingDecision.soundAllowed(context)).isFalse()
        assertThat(RingDecision.vibrationAllowed(context)).isFalse()
    }

    @Test
    fun vibrationOnlyWhenNeitherACallNorDndBlocksIt() {
        all.forEach {
            assertThat(RingDecision.vibrationAllowed(it)).isEqualTo(!it.inRealCall && !it.dndTotalSilence)
        }
    }

    @Test
    fun dndSilencesEveryMode() {
        all.filter { it.dndTotalSilence }.forEach { assertThat(RingDecision.soundAllowed(it)).isFalse() }
        all.filter { !it.dndTotalSilence && !it.inRealCall }.forEach {
            assertThat(RingDecision.soundAllowed(it)).isTrue()
        }
    }

    @Test
    fun truthTableCounts() {
        val counts = all.groupingBy { RingDecision.decide(it) }.eachCount()

        assertThat(counts).containsEntry(RingMode.DEFER_UNTIL_CALL_ENDS, 16)
        assertThat(counts).containsEntry(RingMode.IN_APP_OVERLAY, 4)
        assertThat(counts).containsEntry(RingMode.HEADS_UP_DEGRADED, 6)
        assertThat(counts).containsEntry(RingMode.DND_SILENT_NOTIFICATION, 3)
        assertThat(counts).containsEntry(RingMode.FULL_SCREEN, 3)
    }
}
