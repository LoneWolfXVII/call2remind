package app.call2remind.ringing

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RingLauncherTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val h = EngineHarness()
    private val notifications = RingNotifications(app)

    private val pay = reminder("pay", Schedule.At(T0), title = "Pay rent")
    private val occ = occurrence(pay, T0, state = OccurrenceState.RINGING)

    /** A context whose foreground-service starts are refused, like a background start on API 31+. */
    private val refusingContext = object : ContextWrapper(app) {
        override fun startForegroundService(service: Intent): ComponentName =
            throw ForegroundServiceStartNotAllowedException("not allowed from background")

        override fun getApplicationContext(): Context = this
    }

    private fun launcher(context: Context = app) =
        AndroidRingLauncher(context, h.reminders, h.occurrences, notifications, h.alarms, h.settings, h.clock)

    @Before
    fun setUp() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        h.reminders.upsert(pay)
        h.occurrences.insertIfAbsent(occ)
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun startRingingStartsTheRingingService() = runBlocking<Unit> {
        launcher().startRinging(occ)

        val started = requireNotNull(shadowOf(app).nextStartedService)
        assertThat(started.component?.className).isEqualTo(RingingService::class.java.name)
        assertThat(started.action).isEqualTo(RingingService.ACTION_RING)
        assertThat(started.getStringExtra(RingingService.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
        assertThat(started.getStringExtra(RingingService.EXTRA_TITLE)).isEqualTo("Pay rent")
    }

    @Test
    fun refusedServiceStartFallsBackToASoundingCallNotification() = runBlocking<Unit> {
        launcher(refusingContext).startRinging(occ)

        val posted: Notification = requireNotNull(
            shadowOf(manager).getNotification(RingNotifications.TAG_FALLBACK, notifications.fallbackId(occ)),
        )
        assertThat(posted.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS_FALLBACK)
        assertThat(posted.fullScreenIntent).isNotNull()
        assertThat(posted.category).isEqualTo(Notification.CATEGORY_CALL)

        val log = h.occurrences.getRingLog(occ.id).single()
        assertThat(log.type).isEqualTo(RingLogType.FAILED)
        assertThat(log.reason).isEqualTo("fgs_start_not_allowed")

        // Without a service there is no ring timer: an alarm lets recovery time the ring out.
        val armed = requireNotNull(h.alarms.armed[occ.id])
        assertThat(armed.at).isGreaterThan(T0.plus(h.settings.current().ringTimeout).plus(Duration.ofSeconds(30)))
        assertThat(armed.at).isLessThan(T0.plus(minutes(2)))
    }

    @Test
    fun deferStartsTheServiceWithTheTitles() = runBlocking<Unit> {
        launcher().deferUntilCallEnds(listOf(occ))

        val started = requireNotNull(shadowOf(app).nextStartedService)
        assertThat(started.action).isEqualTo(RingingService.ACTION_DEFER)
        assertThat(started.getStringArrayListExtra(RingingService.EXTRA_TITLES)).containsExactly("Pay rent")
    }

    @Test
    fun refusedDeferPostsASilentHeadsUpAndRechecksWithAnAlarm() = runBlocking<Unit> {
        launcher(refusingContext).deferUntilCallEnds(listOf(occ))

        assertThat(shadowOf(manager).getNotification(RingNotifications.RING_NOTIFICATION_ID)).isNotNull()
        assertThat(h.alarms.armed[occ.id]?.at).isEqualTo(T0.plus(minutes(1)))
    }

    @Test
    fun deferWithNothingDueDoesNothing() = runBlocking<Unit> {
        launcher().deferUntilCallEnds(emptyList())
        assertThat(shadowOf(app).nextStartedService).isNull()
    }

    @Test
    fun missedNotifierPostsResolvesAndClearsFallbacks() = runBlocking<Unit> {
        val notifier = AndroidMissedNotifier(h.reminders, notifications)
        notifications.ensureChannels()
        val missed = occ.copy(state = OccurrenceState.MISSED)

        notifier.onMissed(missed)
        val posted = requireNotNull(shadowOf(manager).getNotification(RingNotifications.TAG_MISSED, notifications.missedId(missed)))
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Missed reminder: Pay rent")

        notifier.onResolved(missed)
        assertThat(shadowOf(manager).getNotification(RingNotifications.TAG_MISSED, notifications.missedId(missed))).isNull()

        launcher(refusingContext).startRinging(occ)
        notifier.onRingEnded(occ)
        assertThat(shadowOf(manager).getNotification(RingNotifications.TAG_FALLBACK, notifications.fallbackId(occ))).isNull()
    }
}
