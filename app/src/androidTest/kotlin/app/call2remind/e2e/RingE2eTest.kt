package app.call2remind.e2e

import android.app.Notification
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.call2remind.R
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.e2e.support.A11y
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Calls
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.Waits
import app.call2remind.e2e.support.e2eLog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The core promise, end to end on a real device: a reminder stored through the engine rings as a
 * full-screen call over the lock screen, and the call screen's accessible controls drive the
 * state machine (answer → done, decline → snooze, ring-back cap → missed).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RingE2eTest {
    @get:Rule
    val e2e = E2eRule()

    @Before
    fun preconditions() {
        assertWithMessage("full-screen intents allowed (appops USE_FULL_SCREEN_INTENT)")
            .that(Device.canUseFullScreenIntent()).isTrue()
    }

    @Test
    fun ringsFullScreenOverLockScreen() {
        val call = AppDriver.scheduleCall("lock screen")
        assertWithMessage("alarm armed in AlarmManager").that(Device.pendingAppAlarmCount()).isAtLeast(1)
        assertWithMessage("soonest occurrence is the next alarm clock").that(AppDriver.nextAlarmClockAt()).isEqualTo(call.fireAt)

        Device.sleepScreen()
        val lockedBeforeRing = Device.isKeyguardLocked
        val row = Calls.awaitFullScreenRing(call)

        assertThat(row.answeredAt).isNull()
        assertThat(AppDriver.logTypes(call.occurrenceId)).containsExactly(RingLogType.FIRED)
        val notification = AppDriver.ringNotification(call.occurrenceId)
        assertWithMessage("incoming-call notification").that(notification).isNotNull()
        assertThat(notification!!.notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notification.notification.fullScreenIntent).isNotNull()
        e2eLog("keyguard locked before ring=$lockedBeforeRing, while ringing=${Device.isKeyguardLocked}")
        if (lockedBeforeRing) {
            assertWithMessage("call screen is shown over the keyguard").that(Device.isKeyguardLocked).isTrue()
        }
    }

    @Test
    fun answerThenDone() {
        val call = AppDriver.scheduleCall("answer")
        Device.sleepScreen()
        Calls.awaitFullScreenRing(call)

        A11y.clickDescription(ANSWER)
        val answered = Waits.value("answered", 15_000) {
            AppDriver.occurrence(call.occurrenceId)?.takeIf { it.answeredAt != null }
        }
        assertThat(answered.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(AppDriver.logTypes(call.occurrenceId)).containsExactly(RingLogType.FIRED, RingLogType.ANSWERED).inOrder()
        // Answered: the service keeps the call as an ongoing (silent) call notification.
        Waits.until("ongoing-call notification", 10_000) {
            val n = AppDriver.ringNotification(call.occurrenceId)?.notification
            n != null && n.fullScreenIntent == null && n.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                (Build.VERSION.SDK_INT < 31 || n.extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_ONGOING)
        }
        assertThat(Device.isRingingServiceForeground()).isTrue()

        A11y.clickText(DONE)
        AppDriver.awaitState(call.occurrenceId, OccurrenceState.DONE)
        Calls.awaitRingEnded()
        assertThat(AppDriver.logTypes(call.occurrenceId)).containsExactly(RingLogType.FIRED, RingLogType.ANSWERED, RingLogType.DONE).inOrder()
        assertThat(AppDriver.ringNotification(call.occurrenceId)).isNull()
    }

    @Test
    fun declineSnoozesForFiveMinutes() {
        val call = AppDriver.scheduleCall("decline")
        Device.sleepScreen()
        Calls.awaitFullScreenRing(call)

        val declinedAt = Instant.now()
        A11y.customAction(ANSWER, DECLINE)
        val snoozed = AppDriver.awaitState(call.occurrenceId, OccurrenceState.SNOOZED)
        Calls.awaitRingEnded()

        assertThat(snoozed.ringBacks).isEqualTo(1)
        val delay = Duration.between(declinedAt, snoozed.fireAt)
        assertWithMessage("snooze delay").that(delay).isAtLeast(Duration.ofMinutes(5).minusSeconds(5))
        assertWithMessage("snooze delay").that(delay).isAtMost(Duration.ofMinutes(5).plusSeconds(20))
        val log = AppDriver.ringLog(call.occurrenceId)
        assertThat(log.map { it.type }).containsExactly(RingLogType.FIRED, RingLogType.SNOOZED).inOrder()
        assertThat(log.last().reason).isEqualTo(RingLogEvent.REASON_DECLINED)
        assertWithMessage("snoozed ring re-armed as the next alarm clock").that(AppDriver.nextAlarmClockAt()).isEqualTo(snoozed.fireAt)
    }

    @Test
    fun ringBackCapMarksMissed() {
        AppDriver.updateSettings { it.copy(snoozeLength = Duration.ofSeconds(25), maxRingBacks = 1) }
        val call = AppDriver.scheduleCall("ring-back cap")
        Device.sleepScreen()
        Calls.awaitFullScreenRing(call)

        A11y.customAction(ANSWER, DECLINE)
        val snoozed = AppDriver.awaitState(call.occurrenceId, OccurrenceState.SNOOZED)
        assertThat(snoozed.ringBacks).isEqualTo(1)
        Calls.awaitRingEnded()

        // The ring-back rings again on its own alarm.
        Device.sleepScreen()
        Calls.awaitFullScreenRing(call, fireAt = snoozed.fireAt)
        assertWithMessage("last ring-back offers no snooze").that(Waits.within(10_000) { A11y.isShown(NO_RING_BACKS_LEFT) }).isTrue()

        A11y.customAction(ANSWER, DECLINE)
        val missed = AppDriver.awaitState(call.occurrenceId, OccurrenceState.MISSED)
        Calls.awaitRingEnded()
        val log = AppDriver.ringLog(call.occurrenceId)
        assertThat(log.map { it.type }).containsExactly(
            RingLogType.FIRED, RingLogType.SNOOZED, RingLogType.FIRED, RingLogType.MISSED,
        ).inOrder()
        assertThat(log.last().reason).isEqualTo(RingLogEvent.REASON_MAX_RING_BACKS)
        assertWithMessage("missed-reminder notification").that(
            Waits.within(10_000) { AppDriver.missedNotification(missed) != null },
        ).isTrue()
    }

    private companion object {
        val ANSWER: String get() = Device.context.getString(R.string.action_answer)
        val DECLINE: String get() = Device.context.getString(R.string.action_decline)
        val DONE: String get() = Device.context.getString(R.string.action_done)
        val NO_RING_BACKS_LEFT: String get() = Device.context.getString(R.string.call_decline_to_missed)
    }
}
