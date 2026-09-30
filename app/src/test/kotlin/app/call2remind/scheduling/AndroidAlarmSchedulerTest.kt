package app.call2remind.scheduling

import android.app.AlarmManager
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.Schedule
import app.call2remind.receivers.AlarmReceiver
import app.call2remind.testing.T0
import app.call2remind.testing.hours
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val shadowAlarms = shadowOf(alarmManager)
    private val scheduler = AndroidAlarmScheduler(context)

    private val first = Occurrence.scheduled(reminder("a", Schedule.At(T0)), T0)
    private val second = Occurrence.scheduled(reminder("b|weird/id?#", Schedule.At(T0)), T0.plus(hours(1)))

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Suppress("DEPRECATION") // ScheduledAlarm.operation has no getter in this Robolectric version.
    private fun alarmFor(occurrence: Occurrence): ShadowAlarmManager.ScheduledAlarm? =
        shadowAlarms.scheduledAlarms.firstOrNull {
            shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID) == occurrence.id
        }

    @Test
    fun soonestUsesSetAlarmClock() {
        scheduler.arm(first, T0, isSoonest = true)

        val alarm = requireNotNull(alarmFor(first))
        assertThat(alarm.alarmClockInfo).isNotNull()
        assertThat(alarm.alarmClockInfo.triggerTime).isEqualTo(T0.toEpochMilli())
        assertThat(alarm.alarmClockInfo.showIntent).isNotNull()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(T0.toEpochMilli())
    }

    @Test
    fun othersUseExactAndAllowWhileIdle() {
        scheduler.arm(second, T0.plus(hours(1)), isSoonest = false)

        val alarm = requireNotNull(alarmFor(second))
        assertThat(alarm.alarmClockInfo).isNull()
        assertThat(alarm.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_EXACT)
        assertThat(alarm.isAllowWhileIdle).isTrue()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(T0.plus(hours(1)).toEpochMilli())
    }

    @Test
    fun withoutExactAlarmAccessFallsBackToInexactWhileIdle() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        scheduler.arm(first, T0, isSoonest = true)

        val alarm = requireNotNull(alarmFor(first))
        assertThat(alarm.alarmClockInfo).isNull()
        assertThat(alarm.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_HEURISTIC)
        assertThat(alarm.isAllowWhileIdle).isTrue()
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12ExactAlarmsNeedNoPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        scheduler.arm(first, T0, isSoonest = true)

        assertThat(requireNotNull(alarmFor(first)).alarmClockInfo).isNotNull()
    }

    @Test
    @Suppress("DEPRECATION")
    fun alarmIntentTargetsTheReceiverWithAUniqueDataUriAndStableRequestCode() {
        scheduler.arm(first, T0, isSoonest = true)
        scheduler.arm(second, T0.plus(hours(1)), isSoonest = false)

        val alarm = requireNotNull(alarmFor(second))
        val pending = shadowOf(alarm.operation)
        assertThat(pending.isBroadcast).isTrue()
        assertThat(pending.isImmutable).isTrue()
        assertThat(pending.requestCode).isEqualTo(second.requestCode)
        val intent = pending.savedIntent
        assertThat(intent.component?.className).isEqualTo(AlarmReceiver::class.java.name)
        assertThat(intent.action).isEqualTo(AlarmReceiver.ACTION_FIRE)
        assertThat(intent.data?.scheme).isEqualTo("c2r")
        assertThat(intent.data?.host).isEqualTo("occ")
        assertThat(intent.data?.lastPathSegment).isEqualTo(second.id)
        assertThat(intent.data).isEqualTo(Uri.parse("c2r://occ/" + Uri.encode(second.id)))
        assertThat(shadowAlarms.scheduledAlarms).hasSize(2)
    }

    @Test
    fun collidingRequestCodesNeverAlias() {
        val twin = second.copy(requestCode = first.requestCode)

        scheduler.arm(first, T0, isSoonest = true)
        scheduler.arm(twin, T0.plus(hours(1)), isSoonest = false)

        assertThat(shadowAlarms.scheduledAlarms).hasSize(2)
        scheduler.cancel(twin)
        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
        assertThat(alarmFor(first)).isNotNull()
    }

    @Test
    fun reArmingReplacesTheAlarm() {
        scheduler.arm(first, T0, isSoonest = true)
        scheduler.arm(first, T0.plus(hours(2)), isSoonest = false)

        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
        val alarm = requireNotNull(alarmFor(first))
        assertThat(alarm.triggerAtMs).isEqualTo(T0.plus(hours(2)).toEpochMilli())
        assertThat(alarm.alarmClockInfo).isNull()
    }

    @Test
    fun cancelRemovesTheAlarmAndIsANoOpWhenNothingIsArmed() {
        scheduler.cancel(first)
        scheduler.arm(first, T0, isSoonest = true)
        scheduler.arm(second, T0.plus(hours(1)), isSoonest = false)

        scheduler.cancel(first)
        scheduler.cancel(first)

        assertThat(alarmFor(first)).isNull()
        assertThat(alarmFor(second)).isNotNull()
    }
}
