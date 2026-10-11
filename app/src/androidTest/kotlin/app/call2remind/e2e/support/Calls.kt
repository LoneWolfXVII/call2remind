package app.call2remind.e2e.support

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.data.db.OccurrenceEntity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.time.Instant

/** Assertions around one ring of a scheduled call. */
object Calls {
    /** How late after its fire time a ring may start and still count as on time. */
    val RING_LATENESS: Duration = Duration.ofSeconds(40)

    private fun msUntil(at: Instant): Long = Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0)

    /** Waits for the call screen showing [title] (in our package) until [deadline]. */
    fun awaitCallScreen(title: String, deadline: Instant): Boolean {
        val shown = Device.ui.wait(Until.hasObject(By.pkg(PKG).text(title)), msUntil(deadline).coerceAtLeast(1_000))
        return shown == true || A11y.isShown(title)
    }

    /**
     * Waits for [call] to ring: occurrence RINGING with a FIRED log entry, within
     * [RING_LATENESS] of its fire time, the ringing service in the foreground. Returns the row.
     */
    fun awaitRinging(call: ScheduledCall, fireAt: Instant = call.fireAt): OccurrenceEntity {
        val row = Waits.value(
            "'${call.title}' ringing (state ${AppDriver.occurrence(call.occurrenceId)?.state})",
            msUntil(fireAt.plus(RING_LATENESS)) + 1_000,
            pollMs = 200,
        ) { AppDriver.occurrence(call.occurrenceId)?.takeIf { it.state == OccurrenceState.RINGING } }
        val fired = AppDriver.ringLog(call.occurrenceId).lastOrNull { it.type == RingLogType.FIRED }
        assertWithMessage("FIRED ring log entry").that(fired).isNotNull()
        val late = Duration.between(fireAt, fired!!.timestamp)
        e2eLog("'${call.title}' FIRED ${late.toMillis()}ms after its fire time")
        assertWithMessage("ring lateness").that(late).isAtMost(RING_LATENESS)
        assertWithMessage("ring started early").that(late.isNegative && late.abs() > Duration.ofSeconds(2)).isFalse()
        Waits.until("RingingService in the foreground", 10_000) { Device.isRingingServiceForeground() }
        return row
    }

    /** [awaitRinging] plus the full-screen call screen over the lock screen, shown in time. */
    fun awaitFullScreenRing(call: ScheduledCall, fireAt: Instant = call.fireAt): OccurrenceEntity {
        val row = awaitRinging(call, fireAt)
        val shown = awaitCallScreen(call.title, fireAt.plus(RING_LATENESS))
        assertWithMessage(
            "IncomingCallActivity showing '${call.title}' within ${RING_LATENESS.seconds}s of fire time " +
                "(resumed=${Device.resumedActivities()}, fsi=${Device.canUseFullScreenIntent()})",
        ).that(shown).isTrue()
        assertWithMessage("IncomingCallActivity resumed (${Device.resumedActivities()})")
            .that(Waits.within(5_000) { Device.isCallActivityResumed() }).isTrue()
        assertThat(Device.isInteractive).isTrue()
        return row
    }

    /** Waits until the ring ended: no ringing service and no call screen. */
    fun awaitRingEnded(timeoutMs: Long = 15_000) {
        Waits.until("ringing service stopped and call screen closed", timeoutMs) {
            !Device.isRingingServiceRunning() && !Device.isCallActivityAlive()
        }
    }
}
