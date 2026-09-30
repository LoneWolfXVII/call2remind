package app.call2remind.receivers

import android.app.Application
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.ringing.RingWakeLock
import app.call2remind.ringing.RingingService
import app.call2remind.scheduling.AndroidAlarmScheduler
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.testing.FakeAlarmScheduler
import app.call2remind.testing.FakeBackgroundJobs
import app.call2remind.testing.FakeRingContextProvider
import app.call2remind.testing.MutableClock
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.hours
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ReceiversTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var occurrences: OccurrenceRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var clock: MutableClock

    @Inject lateinit var alarms: FakeAlarmScheduler

    @Inject lateinit var ringContext: FakeRingContextProvider

    @Inject lateinit var jobs: FakeBackgroundJobs

    @Inject lateinit var db: Call2RemindDb

    @Inject lateinit var wakeLock: RingWakeLock

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() = db.close()

    private fun scheduled(externalId: String, at: Instant): Occurrence = runBlocking {
        val r = reminder(externalId, Schedule.At(at))
        engine.upsertReminders(listOf(r))
        Occurrence.scheduled(r, at)
    }

    private fun ringing(externalId: String = "a"): Occurrence {
        val occ = scheduled(externalId, clock.now)
        runBlocking { check(occurrences.tryClaimRing(occ.id, clock.now)) }
        return occ
    }

    private fun state(id: String): Occurrence? = runBlocking { occurrences.get(id) }

    private fun logTypes(id: String): List<RingLogType> = runBlocking { occurrences.getRingLog(id).map { it.type } }

    private fun action(action: String, id: String?, snoozeMinutes: Long? = null): Intent =
        Intent(app, CallActionReceiver::class.java).setAction(action).apply {
            if (id != null) putExtra(CallActionReceiver.EXTRA_OCCURRENCE_ID, id)
            if (snoozeMinutes != null) putExtra(CallActionReceiver.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        }

    private fun send(intent: Intent) = CallActionReceiver().onReceive(app, intent)

    // --- CallActionReceiver ---

    @Test
    fun answerMarksTheRingAnswered() {
        val occ = ringing()

        send(action(CallActionReceiver.ACTION_ANSWER, occ.id))

        awaitUntil { state(occ.id)?.answeredAt != null }
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.RINGING)
    }

    @Test
    fun declineSnoozesForTheDefaultLength() {
        val occ = ringing()

        send(action(CallActionReceiver.ACTION_DECLINE, occ.id))

        awaitUntil { state(occ.id)?.state == OccurrenceState.SNOOZED }
        assertThat(state(occ.id)?.fireAt).isEqualTo(clock.now.plus(minutes(5)))
        awaitUntil(message = "snooze alarm") { alarms.armed[occ.id]?.at == clock.now.plus(minutes(5)) }
    }

    @Test
    fun snoozeUsesTheRequestedMinutes() {
        val occ = ringing()

        send(action(CallActionReceiver.ACTION_SNOOZE, occ.id, snoozeMinutes = 15))

        awaitUntil { state(occ.id)?.state == OccurrenceState.SNOOZED }
        assertThat(state(occ.id)?.fireAt).isEqualTo(clock.now.plus(minutes(15)))
    }

    @Test
    fun snoozeWithoutMinutesUsesTheConfiguredLength() {
        runBlocking { settings.update { it.copy(snoozeLength = minutes(7)) } }
        val occ = ringing()

        send(action(CallActionReceiver.ACTION_SNOOZE, occ.id))

        awaitUntil { state(occ.id)?.state == OccurrenceState.SNOOZED }
        assertThat(state(occ.id)?.fireAt).isEqualTo(clock.now.plus(minutes(7)))
    }

    @Test
    fun doneAndSkipFinishTheOccurrence() {
        val occ = ringing("a")
        val later = scheduled("b", clock.now.plus(hours(2)))

        send(action(CallActionReceiver.ACTION_DONE, occ.id))
        awaitUntil { state(occ.id)?.state == OccurrenceState.DONE }
        send(action(CallActionReceiver.ACTION_SKIP, later.id))
        awaitUntil { state(later.id)?.state == OccurrenceState.SKIPPED }

        awaitUntil { alarms.armed.isEmpty() }
    }

    @Test
    fun unknownActionsAndMissingIdsAreIgnored() {
        val occ = ringing()

        send(action("app.call2remind.action.NOPE", occ.id))
        send(action(CallActionReceiver.ACTION_DONE, null))
        send(action(CallActionReceiver.ACTION_DECLINE, occ.id))

        awaitUntil { state(occ.id)?.state == OccurrenceState.SNOOZED }
        assertThat(logTypes(occ.id)).containsExactly(RingLogType.FIRED, RingLogType.SNOOZED).inOrder()
    }

    // --- AlarmReceiver ---

    private fun fire(occ: Occurrence) = AlarmReceiver().onReceive(app, AndroidAlarmScheduler.fireIntent(app, occ.id))

    @Test
    @Config(sdk = [34, 35])
    fun alarmClaimsTheDueOccurrenceAndStartsTheRingingService() {
        val occ = scheduled("a", clock.now)

        fire(occ)

        awaitUntil { shadowOf(app).peekNextStartedService() != null }
        val started = shadowOf(app).nextStartedService
        assertThat(started.action).isEqualTo(RingingService.ACTION_RING)
        assertThat(started.getStringExtra(RingingService.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(logTypes(occ.id)).containsExactly(RingLogType.FIRED)
        // The CPU stays awake until the service rings; the ring is guarded by its deadline alarm.
        assertThat(wakeLock.isHeld).isTrue()
        awaitUntil(message = "deadline alarm") { alarms.armed[occ.id]?.at == clock.now.plus(Duration.ofSeconds(76)) }
    }

    @Test
    fun alarmWhileAnotherOccurrenceRingsWithoutAServiceRingsItAtTheDeadline() {
        val first = scheduled("a", clock.now)
        val second = scheduled("b", clock.now.plusSeconds(5))
        fire(first)
        awaitUntil { shadowOf(app).peekNextStartedService() != null }
        shadowOf(app).clearStartedServices()
        // The ringing service never runs (process killed). The second alarm finds the line busy.
        clock.advance(Duration.ofSeconds(6))
        fire(second)
        Thread.sleep(IGNORE_SETTLE_MS)
        assertThat(shadowOf(app).peekNextStartedService()).isNull()
        assertThat(state(second.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)

        // The ringing occurrence's deadline alarm times it out and rings the queued one.
        clock.now = requireNotNull(alarms.armed[first.id]).at
        fire(first)

        awaitUntil(message = "queued ring started") { shadowOf(app).peekNextStartedService() != null }
        assertThat(shadowOf(app).nextStartedService.getStringExtra(RingingService.EXTRA_OCCURRENCE_ID)).isEqualTo(second.id)
        assertThat(state(first.id)?.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(state(second.id)?.state).isEqualTo(OccurrenceState.RINGING)
    }

    @Test
    fun alarmDuringARealCallDefers() {
        val occ = scheduled("a", clock.now)
        ringContext.context = FakeRingContextProvider.IDLE.copy(inRealCall = true)

        AlarmReceiver().onReceive(app, AndroidAlarmScheduler.fireIntent(app, occ.id))

        awaitUntil { shadowOf(app).peekNextStartedService() != null }
        assertThat(shadowOf(app).nextStartedService.action).isEqualTo(RingingService.ACTION_DEFER)
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(logTypes(occ.id)).containsExactly(RingLogType.DEFERRED)
    }

    @Test
    fun alarmWithAnotherActionIsIgnored() {
        val occ = scheduled("a", clock.now)

        AlarmReceiver().onReceive(app, Intent("something.else"))

        Thread.sleep(IGNORE_SETTLE_MS)
        assertThat(shadowOf(app).peekNextStartedService()).isNull()
        assertThat(state(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
    }

    // --- BootReceiver / TimeChangeReceiver ---

    @Test
    fun bootRecoversRecentMissesAndSchedulesBackgroundJobs() {
        val occ = scheduled("a", clock.now.plus(hours(1)))
        clock.advance(hours(1).plus(minutes(30)))
        alarms.reset()

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        awaitUntil { jobs.scheduledCount == 1 }
        assertThat(alarms.armed[occ.id]?.at).isEqualTo(clock.now)
    }

    @Test
    fun lockedBootAndPackageReplacedAlsoRecover() {
        val userManager = app.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(false)
        val occ = scheduled("a", clock.now.plus(hours(1)))
        clock.advance(hours(4))

        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        awaitUntil { state(occ.id)?.state == OccurrenceState.MISSED }
        Thread.sleep(IGNORE_SETTLE_MS)
        // WorkManager lives in credential-protected storage: untouched before the first unlock.
        assertThat(jobs.scheduledCount).isEqualTo(0)

        shadowOf(userManager).setUserUnlocked(true)
        val next = scheduled("b", clock.now.plus(hours(1)))
        clock.advance(hours(1).plus(minutes(1)))
        alarms.reset()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        awaitUntil { alarms.armed[next.id]?.at == clock.now }
        awaitUntil { jobs.scheduledCount == 1 }
    }

    @Test
    fun timeChangeRecoversRingsTheClockJumpedOver() {
        val occ = scheduled("a", clock.now.plus(hours(1)))
        clock.advance(hours(1).plus(minutes(10)))
        alarms.reset()

        TimeChangeReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))

        awaitUntil { alarms.armed[occ.id]?.at == clock.now }
    }

    @Test
    fun exactAlarmPermissionGrantReArmsAlarms() {
        val occ = scheduled("a", clock.now.plus(hours(1)))
        alarms.reset()

        TimeChangeReceiver().onReceive(app, Intent(TimeChangeReceiver.ACTION_EXACT_ALARM_PERMISSION_CHANGED))

        awaitUntil { alarms.armed[occ.id]?.at == clock.now.plus(hours(1)) }
    }

    private companion object {
        const val IGNORE_SETTLE_MS = 200L
    }
}
