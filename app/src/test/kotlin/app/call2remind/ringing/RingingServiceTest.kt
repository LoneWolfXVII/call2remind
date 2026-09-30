package app.call2remind.ringing

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.testing.FakeCallStateMonitor
import app.call2remind.testing.FakeRingAlerts
import app.call2remind.testing.FakeRingContextProvider
import app.call2remind.testing.FakeTtsPlayer
import app.call2remind.testing.MutableClock
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.minutes
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService
import java.time.Duration
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class RingingServiceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var occurrences: OccurrenceRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var clock: MutableClock

    @Inject lateinit var ringContext: FakeRingContextProvider

    @Inject lateinit var alerts: FakeRingAlerts

    @Inject lateinit var tts: FakeTtsPlayer

    @Inject lateinit var callState: FakeCallStateMonitor

    @Inject lateinit var db: Call2RemindDb

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private var controller: ServiceController<RingingService>? = null

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        db.close()
    }

    private fun dueOccurrence(externalId: String, title: String, sourceType: SourceType = SourceType.HABIT): Occurrence =
        runBlocking {
            val r = reminder(externalId, Schedule.At(clock.now), sourceType = sourceType, title = title)
            engine.upsertReminders(listOf(r))
            Occurrence.scheduled(r, clock.now)
        }

    private fun state(id: String): Occurrence? = runBlocking { occurrences.get(id) }

    private fun startService(intent: android.content.Intent): Pair<RingingService, ShadowService> {
        val built = Robolectric.buildService(RingingService::class.java, intent)
        controller = built
        built.create().startCommand(0, 1)
        return built.get() to shadowOf(built.get())
    }

    /** Claims the ring lock for [occurrence] (as the alarm receiver does) and starts the service. */
    private fun ring(occurrence: Occurrence, title: String): Pair<RingingService, ShadowService> {
        runBlocking { check(occurrences.tryClaimRing(occurrence.id, clock.now)) }
        val started = startService(RingingService.ringIntent(app, occurrence.id, title))
        awaitUntil(message = "alerts started") { alerts.active != null }
        return started
    }

    private val Notification.callType: Int get() = extras.getInt(Notification.EXTRA_CALL_TYPE)

    private val Notification.title: String get() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun ringsAsAForegroundIncomingCallWithFullScreenIntentAndAlerts() {
        val occ = dueOccurrence("a", "Pay rent")

        val (service, shadow) = ring(occ, "Pay rent")

        val notification = requireNotNull(shadow.lastForegroundNotification)
        assertThat(shadow.lastForegroundNotificationId).isEqualTo(RingNotifications.RING_NOTIFICATION_ID)
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        assertThat(notification.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS)
        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).isEqualTo(Notification.CallStyle::class.java.name)
        assertThat(notification.callType).isEqualTo(Notification.CallStyle.CALL_TYPE_INCOMING)
        assertThat(notification.fullScreenIntent).isNotNull()
        assertThat(notification.title).isEqualTo("Pay rent")
        assertThat(alerts.active).isEqualTo(FakeRingAlerts.Start(ringtoneUri = null, sound = true, vibrate = true))
        assertThat(notificationManager.getNotificationChannel(RingNotifications.CHANNEL_CALLS)).isNotNull()
    }

    @Test
    fun answeringStopsTheRingtoneSwitchesToOngoingAndSpeaks() {
        val occ = dueOccurrence("a", "Pay rent")
        val (_, shadow) = ring(occ, "Pay rent")

        runBlocking { engine.handle(occ.id, OccurrenceEvent.Answer) }

        awaitUntil(message = "tts spoken") { alerts.active == null && tts.spoken.isNotEmpty() }
        assertThat(tts.spoken.single()).startsWith("Reminder: Pay rent.")
        awaitUntil(message = "ongoing notification") { shadow.lastForegroundNotification?.callType == Notification.CallStyle.CALL_TYPE_ONGOING }
        assertThat(shadow.isStoppedBySelf).isFalse()
    }

    @Test
    fun declineEndsTheRingAndStopsTheService() {
        val occ = dueOccurrence("a", "Pay rent")
        val (_, shadow) = ring(occ, "Pay rent")

        runBlocking { engine.handle(occ.id, OccurrenceEvent.Decline) }

        awaitUntil(message = "service stopped") { shadow.isStoppedBySelf }
        assertThat(shadow.isForegroundStopped).isTrue()
        assertThat(alerts.active).isNull()
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.SNOOZED)
    }

    @Test
    fun doneEndsTheRingAndStopsTheService() {
        val occ = dueOccurrence("a", "Pay rent")
        val (_, shadow) = ring(occ, "Pay rent")
        runBlocking { engine.handle(occ.id, OccurrenceEvent.Answer) }

        runBlocking { engine.handle(occ.id, OccurrenceEvent.Done) }

        awaitUntil(message = "service stopped") { shadow.isStoppedBySelf }
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.DONE)
    }

    @Test
    fun unansweredRingTimesOutIntoASnoozeAndAMissedNotice() {
        val occ = dueOccurrence("a", "Pay rent")
        val (_, shadow) = ring(occ, "Pay rent")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46))

        awaitUntil(message = "timed out") { state(occ.id)?.state == OccurrenceState.SNOOZED }
        val log = runBlocking { occurrences.getRingLog(occ.id) }
        assertThat(log.last().reason).isEqualTo(RingLogEvent.REASON_RING_TIMEOUT)
        assertThat(state(occ.id)?.ringBacks).isEqualTo(1)
        awaitUntil(message = "service stopped") { shadow.isStoppedBySelf }
        assertThat(shadowOf(notificationManager).getNotification(RingNotifications.TAG_MISSED, occ.requestCode)).isNotNull()
    }

    @Test
    fun doNotDisturbRingsSilentlyWithVibrationAndShowsTheHintOnce() {
        ringContext.context = FakeRingContextProvider.IDLE.copy(dndTotalSilence = true)
        val occ = dueOccurrence("a", "Pay rent")

        val (_, shadow) = ring(occ, "Pay rent")

        assertThat(alerts.active).isEqualTo(FakeRingAlerts.Start(ringtoneUri = null, sound = false, vibrate = true))
        assertThat(shadow.lastForegroundNotification?.fullScreenIntent).isNotNull()
        assertThat(shadowOf(notificationManager).getNotification(RingNotifications.DND_HINT_ID)).isNotNull()
        assertThat(runBlocking { settings.current() }.onboarding.dndHintShown).isTrue()
    }

    @Test
    fun withoutFullScreenIntentPermissionItRingsAsAHeadsUp() {
        ringContext.context = FakeRingContextProvider.IDLE.copy(canUseFullScreenIntent = false)
        val occ = dueOccurrence("a", "Pay rent")

        val (_, shadow) = ring(occ, "Pay rent")

        assertThat(shadow.lastForegroundNotification?.fullScreenIntent).isNull()
        assertThat(alerts.active?.sound).isTrue()
    }

    @Test
    fun visibleAppShowsTheCallScreenDirectly() {
        ringContext.context = FakeRingContextProvider.IDLE.copy(appInForeground = true)
        val occ = dueOccurrence("a", "Pay rent")

        ring(occ, "Pay rent")

        val started = requireNotNull(shadowOf(app).nextStartedActivity)
        assertThat(started.component?.className).isEqualTo(IncomingCallActivity::class.java.name)
        assertThat(started.getStringExtra(IncomingCallActivity.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
    }

    @Test
    fun ringsQueuedOccurrencesOneAfterAnotherInTheSameService() {
        val meeting = dueOccurrence("m", "Meeting", SourceType.CALENDAR)
        val habit = dueOccurrence("h", "Stretch", SourceType.HABIT)
        val (_, shadow) = ring(meeting, "Meeting")
        assertThat(state(habit.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)

        runBlocking { engine.handle(meeting.id, OccurrenceEvent.Skip) }

        awaitUntil(message = "second ring") { state(habit.id)?.state == OccurrenceState.RINGING }
        awaitUntil(message = "second notification") { shadow.lastForegroundNotification?.title == "Stretch" }
        assertThat(shadow.isStoppedBySelf).isFalse()
        assertThat(alerts.starts).hasSize(2)
    }

    @Test
    fun deferWaitsForTheRealCallToEndThenRings() {
        callState.inCall.value = true
        ringContext.context = FakeRingContextProvider.IDLE.copy(inRealCall = true)
        val occ = dueOccurrence("a", "Pay rent")

        val (_, shadow) = startService(RingingService.deferIntent(app, listOf("Pay rent")))

        val deferred = requireNotNull(shadow.lastForegroundNotification)
        assertThat(deferred.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("Pay rent")
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)

        clock.advance(minutes(3))
        ringContext.context = FakeRingContextProvider.IDLE
        callState.inCall.value = false

        awaitUntil(message = "rings after the call") { state(occ.id)?.state == OccurrenceState.RINGING && alerts.active != null }
        assertThat(shadow.lastForegroundNotification?.callType).isEqualTo(Notification.CallStyle.CALL_TYPE_INCOMING)
    }
}
