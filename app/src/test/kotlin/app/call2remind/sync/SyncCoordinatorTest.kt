package app.call2remind.sync

import app.cash.turbine.test
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.sync.Backoff
import app.call2remind.data.model.ReminderIds
import app.call2remind.settings.Settings
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.NotConnectedException
import app.call2remind.sources.PermanentSyncException
import app.call2remind.sources.RetryableSyncException
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceIds
import app.call2remind.sources.SourceSnapshot
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeNotificationAccess
import app.call2remind.testing.FakeReminderSource
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.awaitItemMatching
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Duration
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {
    private val h = EngineHarness(initialSettings = Settings(sources = SourceSettings()))
    private val calendar = FakeReminderSource(SourceType.CALENDAR)
    private val birthdays = FakeReminderSource(SourceType.BIRTHDAY)
    private val tasks = FakeReminderSource(SourceType.GOOGLE_TASKS, requiresNetwork = true)
    private val access = FakeNotificationAccess()

    /** Full-jitter backoff that always picks the ceiling, so delays are deterministic. */
    private val maxJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextLong(from: Long, until: Long): Long = until - 1
    }
    private val coordinator = SyncCoordinator(
        setOf(calendar, birthdays, tasks),
        h.sources,
        h.reminders,
        h.engine,
        h.settings,
        access,
        h.clock,
        Backoff(base = minutes(1), cap = hours(1), random = maxJitter),
    ) { UTC }

    private fun event(id: String, inHours: Long = 2): Reminder =
        reminder(id, Schedule.At(T0.plus(hours(inHours))), sourceType = SourceType.CALENDAR, title = "Event $id")

    private fun task(id: String, inHours: Long = 3): Reminder =
        reminder(id, Schedule.At(T0.plus(hours(inHours))), sourceType = SourceType.GOOGLE_TASKS)

    @After
    fun tearDown() = h.close()

    @Test
    fun fullSnapshotIsStoredMarkedSyncedAndPlanned() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1"), event("e2", 5)), cursor = "c1") }

        val result = coordinator.sync(SyncScope.LOCAL)

        assertThat(result.outcomes).containsExactly(
            SourceType.CALENDAR, SourceOutcome.Synced,
            SourceType.BIRTHDAY, SourceOutcome.Synced,
        )
        assertThat(result.needsRetry).isFalse()
        assertThat(h.reminders.getAll().map { it.externalId }).containsExactly("e1", "e2")
        val row = h.sources.get(SourceIds.CALENDAR)
        assertThat(row?.type).isEqualTo(SourceType.CALENDAR)
        assertThat(row?.lastSyncAt).isEqualTo(T0)
        assertThat(row?.syncCursor).isEqualTo("c1")
        assertThat(h.occurrences.getPending().map { it.fireAt }).containsExactly(T0.plus(hours(2)), T0.plus(hours(5))).inOrder()
        assertThat(tasks.requests).isEmpty()
    }

    @Test
    fun requestCarriesCursorZoneSettingsAndKnownIds() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1")), cursor = "c1") }
        coordinator.sync(setOf(SourceType.CALENDAR))
        h.clock.advance(minutes(15))

        coordinator.sync(setOf(SourceType.CALENDAR))

        val second = calendar.requests.last()
        assertThat(second.cursor).isEqualTo("c1")
        assertThat(second.now).isEqualTo(T0.plus(minutes(15)))
        assertThat(second.zone).isEqualTo(UTC)
        assertThat(second.knownExternalIds).containsExactly("e1")
        assertThat(second.settings).isEqualTo(SourceSettings())
        assertThat(calendar.requests.first().cursor).isNull()
    }

    @Test
    fun fullSnapshotDeletesRemindersThatDisappeared() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1"), event("e2"))) }
        coordinator.sync(setOf(SourceType.CALENDAR))
        calendar.next = { SourceSnapshot.Full(listOf(event("e2"))) }

        coordinator.sync(setOf(SourceType.CALENDAR))

        assertThat(h.reminders.getAll().map { it.externalId }).containsExactly("e2")
        assertThat(h.occurrences.getPending()).hasSize(1)
    }

    @Test
    fun aReminderTheUserTurnedOffStaysOffAcrossSyncs() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1"), event("e2", 5))) }
        coordinator.sync(setOf(SourceType.CALENDAR))
        val e1 = h.reminders.getAll().single { it.externalId == "e1" }
        h.engine.upsertReminders(listOf(e1.copy(enabled = false)), SourceIds.CALENDAR)
        assertThat(h.occurrences.getPending()).hasSize(1)

        coordinator.sync(setOf(SourceType.CALENDAR))

        assertThat(h.reminders.getAll().single { it.externalId == "e1" }.enabled).isFalse()
        assertThat(h.reminders.getAll().single { it.externalId == "e2" }.enabled).isTrue()
        assertThat(h.occurrences.getPending().map { it.fireAt }).containsExactly(T0.plus(hours(5)))
    }

    @Test
    fun deltaUpsertsAndDeletes() = runBlocking<Unit> {
        tasks.next = { SourceSnapshot.Full(listOf(task("t1"), task("t2")), cursor = "v1") }
        coordinator.sync(SyncScope.CLOUD)
        tasks.next = { SourceSnapshot.Delta(upserts = listOf(task("t3", 4)), removedExternalIds = setOf("t1", "unknown"), cursor = "v2") }

        val result = coordinator.sync(SyncScope.CLOUD)

        assertThat(result.outcomes).containsExactly(SourceType.GOOGLE_TASKS, SourceOutcome.Synced)
        assertThat(h.reminders.getAll().map { it.externalId }).containsExactly("t2", "t3")
        assertThat(h.sources.get(SourceIds.GOOGLE_TASKS)?.syncCursor).isEqualTo("v2")
        assertThat(h.occurrences.getPending().map { it.fireAt }).containsExactly(T0.plus(hours(3)), T0.plus(hours(4)))
    }

    @Test
    fun oneFailingSourceDoesNotBlockTheOthers() = runBlocking<Unit> {
        calendar.next = { throw IllegalStateException("provider crashed") }
        birthdays.next = { SourceSnapshot.Full(listOf(reminder("b", Schedule.At(T0.plus(hours(6))), sourceType = SourceType.BIRTHDAY))) }
        tasks.next = { SourceSnapshot.Full(listOf(task("t1"))) }

        val result = coordinator.sync(SyncScope.ALL)

        assertThat(result.outcomes[SourceType.CALENDAR]).isEqualTo(SourceOutcome.Failed(SyncState.Error("provider crashed", retryable = false)))
        assertThat(result.outcomes[SourceType.BIRTHDAY]).isEqualTo(SourceOutcome.Synced)
        assertThat(result.outcomes[SourceType.GOOGLE_TASKS]).isEqualTo(SourceOutcome.Synced)
        assertThat(result.needsRetry).isFalse()
        assertThat(h.reminders.getAll().map { it.sourceType }).containsExactly(SourceType.BIRTHDAY, SourceType.GOOGLE_TASKS)
        assertThat(h.sources.get(SourceIds.CALENDAR)?.lastSyncAt).isNull()
        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(SyncState.Error("provider crashed", false))
    }

    @Test
    fun retryableFailuresAskForARetry() = runBlocking<Unit> {
        tasks.next = { throw RetryableSyncException("HTTP 503") }

        val result = coordinator.sync(SyncScope.CLOUD)

        assertThat(result.needsRetry).isTrue()
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.Error("HTTP 503", retryable = true))

        tasks.next = { throw IOException("offline") }
        assertThat(coordinator.sync(SyncScope.CLOUD, force = true).needsRetry).isTrue()
        tasks.next = { throw PermanentSyncException("HTTP 404") }
        assertThat(coordinator.sync(SyncScope.CLOUD, force = true).needsRetry).isFalse()
    }

    @Test
    fun failingSourceBacksOffUnlessForced() = runBlocking<Unit> {
        tasks.next = { throw RetryableSyncException("HTTP 503") }
        coordinator.sync(SyncScope.CLOUD)
        tasks.next = { SourceSnapshot.Full(listOf(task("t1"))) }

        // First retry is allowed 1 minute after the failure.
        h.clock.advance(Duration.ofSeconds(30))
        val early = coordinator.sync(SyncScope.CLOUD)
        assertThat(early.outcomes[SourceType.GOOGLE_TASKS])
            .isEqualTo(SourceOutcome.Skipped(SyncState.Error("HTTP 503", retryable = true)))
        assertThat(tasks.requests).hasSize(1)

        h.clock.advance(Duration.ofSeconds(30))
        val later = coordinator.sync(SyncScope.CLOUD)

        assertThat(later.outcomes[SourceType.GOOGLE_TASKS]).isEqualTo(SourceOutcome.Synced)
        assertThat(tasks.requests).hasSize(2)
    }

    @Test
    fun backoffSkipsNonForcedRunsAndGrows() = runBlocking<Unit> {
        tasks.next = { throw RetryableSyncException("HTTP 503") }
        // Five failures in a row (forced runs ignore the gate) push the next allowed attempt out.
        repeat(5) { coordinator.sync(setOf(SourceType.GOOGLE_TASKS), force = true) }
        // The fifth failure waits 16 minutes (1 min * 2^4).
        h.clock.advance(Duration.ofMinutes(15))
        val requestsBefore = tasks.requests.size

        val skipped = coordinator.sync(SyncScope.CLOUD)

        assertThat(skipped.outcomes[SourceType.GOOGLE_TASKS]).isInstanceOf(SourceOutcome.Skipped::class.java)
        assertThat(tasks.requests).hasSize(requestsBefore)
        assertThat(skipped.needsRetry).isFalse()

        tasks.next = { SourceSnapshot.Full(emptyList()) }
        assertThat(coordinator.sync(setOf(SourceType.GOOGLE_TASKS), force = true).outcomes[SourceType.GOOGLE_TASKS])
            .isEqualTo(SourceOutcome.Synced)
        // Success resets the backoff.
        assertThat(coordinator.sync(SyncScope.CLOUD).outcomes[SourceType.GOOGLE_TASKS]).isEqualTo(SourceOutcome.Synced)
    }

    @Test
    fun missingPermissionIsReportedAndKeepsStoredReminders() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1"))) }
        coordinator.sync(setOf(SourceType.CALENDAR))
        calendar.availability = SourceAvailability.NeedsPermission("android.permission.READ_CALENDAR")

        val result = coordinator.sync(setOf(SourceType.CALENDAR))

        val state = SyncState.NeedsPermission("android.permission.READ_CALENDAR")
        assertThat(result.outcomes[SourceType.CALENDAR]).isEqualTo(SourceOutcome.Skipped(state))
        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(state)
        assertThat(h.reminders.getAll()).hasSize(1)
        assertThat(calendar.requests).hasSize(1)
    }

    @Test
    fun permissionRevokedDuringReadIsReportedToo() = runBlocking<Unit> {
        calendar.next = { throw NeedsPermissionException("android.permission.READ_CALENDAR") }

        coordinator.sync(setOf(SourceType.CALENDAR))

        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(SyncState.NeedsPermission("android.permission.READ_CALENDAR"))
    }

    @Test
    fun notConnectedCloudSourceIsSkipped() = runBlocking<Unit> {
        tasks.availability = SourceAvailability.NotConnected

        val result = coordinator.sync(SyncScope.CLOUD)

        assertThat(result.outcomes[SourceType.GOOGLE_TASKS]).isEqualTo(SourceOutcome.Skipped(SyncState.NotConnected))
        assertThat(tasks.requests).isEmpty()
        assertThat(result.needsRetry).isFalse()

        tasks.availability = SourceAvailability.Ready
        tasks.next = { throw NotConnectedException("Authorization rejected (HTTP 401)") }
        coordinator.sync(SyncScope.CLOUD)
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.NotConnected)
    }

    @Test
    fun disabledSourceIsNotReadAndItsRemindersAreRemoved() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(event("e1"))) }
        coordinator.sync(setOf(SourceType.CALENDAR))
        assertThat(h.occurrences.getPending()).hasSize(1)
        h.settings.update { it.copy(sources = it.sources.withEnabled(SourceType.CALENDAR, false)) }

        val result = coordinator.sync(SyncScope.LOCAL)

        assertThat(result.outcomes[SourceType.CALENDAR]).isEqualTo(SourceOutcome.Skipped(SyncState.Disabled))
        assertThat(calendar.requests).hasSize(1)
        assertThat(h.reminders.getAll()).isEmpty()
        assertThat(h.sources.get(SourceIds.CALENDAR)).isNull()
        assertThat(h.occurrences.getPending()).isEmpty()
        assertThat(h.alarms.armed).isEmpty()
    }

    @Test
    fun setEnabledTogglesTheSettingAndSyncsOrClears() = runBlocking<Unit> {
        tasks.next = { SourceSnapshot.Full(listOf(task("t1"))) }

        coordinator.setEnabled(SourceType.GOOGLE_TASKS, false)
        assertThat(h.settings.current().sources.isEnabled(SourceType.GOOGLE_TASKS)).isFalse()
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.Disabled)

        val enabled = coordinator.setEnabled(SourceType.GOOGLE_TASKS, true)
        assertThat(enabled.outcomes[SourceType.GOOGLE_TASKS]).isEqualTo(SourceOutcome.Synced)
        assertThat(h.occurrences.getPending()).hasSize(1)

        coordinator.setEnabled(SourceType.GOOGLE_TASKS, false)
        assertThat(h.reminders.getAll()).isEmpty()
        assertThat(h.occurrences.getPending()).isEmpty()
    }

    @Test
    fun disconnectForgetsTheAccountAndItsReminders() = runBlocking<Unit> {
        tasks.next = { SourceSnapshot.Full(listOf(task("t1"))) }
        coordinator.sync(SyncScope.CLOUD)

        coordinator.disconnect(SourceType.GOOGLE_TASKS)

        assertThat(tasks.disconnected).isEqualTo(1)
        assertThat(h.reminders.getAll()).isEmpty()
        assertThat(h.occurrences.getPending()).isEmpty()
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.NotConnected)
    }

    @Test
    fun samsungStatusFollowsSettingAndNotificationAccess() = runBlocking<Unit> {
        coordinator.refreshStatuses()
        assertThat(coordinator.currentState(SourceType.SAMSUNG_REMINDER)).isEqualTo(SyncState.Disabled)

        coordinator.setEnabled(SourceType.SAMSUNG_REMINDER, true)
        assertThat(coordinator.currentState(SourceType.SAMSUNG_REMINDER))
            .isEqualTo(SyncState.NeedsPermission(SyncCoordinator.NOTIFICATION_LISTENER_PERMISSION))

        access.granted = true
        coordinator.refreshStatuses()
        assertThat(coordinator.currentState(SourceType.SAMSUNG_REMINDER)).isEqualTo(SyncState.Idle)
    }

    @Test
    fun refreshStatusesReflectsPermissionChangesWithoutSyncing() = runBlocking<Unit> {
        calendar.availability = SourceAvailability.NeedsPermission("p")
        tasks.availability = SourceAvailability.NotConnected
        coordinator.refreshStatuses()

        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(SyncState.NeedsPermission("p"))
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.NotConnected)
        assertThat(coordinator.currentState(SourceType.BIRTHDAY)).isEqualTo(SyncState.Idle)

        calendar.availability = SourceAvailability.Ready
        coordinator.refreshStatuses()
        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(SyncState.Idle)
        assertThat(calendar.requests).isEmpty()
    }

    @Test
    fun statusFlowCombinesStateAndLastSync() = runBlocking<Unit> {
        assertThat(coordinator.types).containsExactly(
            SourceType.CALENDAR, SourceType.SAMSUNG_REMINDER, SourceType.GOOGLE_TASKS, SourceType.BIRTHDAY,
        ).inOrder()

        coordinator.status(SourceType.CALENDAR).test {
            assertThat(awaitItem()).isEqualTo(SyncStatus(SourceType.CALENDAR, SourceIds.CALENDAR, SyncState.Idle, null))
            calendar.next = { throw IllegalStateException("boom") }
            coordinator.sync(setOf(SourceType.CALENDAR))
            assertThat(awaitItemMatching { it.state is SyncState.Error }.lastSyncAt).isNull()

            calendar.next = { SourceSnapshot.Full(emptyList()) }
            coordinator.sync(setOf(SourceType.CALENDAR))
            val synced = awaitItemMatching { it.state == SyncState.Idle && it.lastSyncAt != null }
            assertThat(synced.lastSyncAt).isEqualTo(T0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun statusesListEveryType() = runBlocking<Unit> {
        coordinator.statuses.test {
            val first = awaitItem()
            assertThat(first.map { it.type }).isEqualTo(coordinator.types)
            assertThat(first.map { it.sourceId }).containsExactly("calendar", "samsung_reminders", "google_tasks", "birthdays").inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun localHousekeepingPrunesOldSamsungReminders() = runBlocking<Unit> {
        val old = reminder("old", Schedule.At(T0.minus(Duration.ofDays(31))), sourceType = SourceType.SAMSUNG_REMINDER)
        val recent = reminder("recent", Schedule.At(T0.minus(Duration.ofDays(2))), sourceType = SourceType.SAMSUNG_REMINDER)
        h.reminders.upsertAll(listOf(old, recent), SourceIds.SAMSUNG_REMINDERS)

        coordinator.sync(SyncScope.CLOUD)
        assertThat(h.reminders.getAll()).hasSize(2)

        coordinator.sync(SyncScope.LOCAL)
        assertThat(h.reminders.getAll().map { it.id }).containsExactly(ReminderIds.of(SourceType.SAMSUNG_REMINDER, "recent"))
    }

    @Test
    fun availabilityCheckCrashDoesNotStopTheSync() = runBlocking<Unit> {
        val flaky = object : app.call2remind.sources.ReminderSource by FakeReminderSource(SourceType.MS_TODO, requiresNetwork = true) {
            override suspend fun availability(): SourceAvailability = error("token store unavailable")
        }
        val c = SyncCoordinator(setOf(flaky), h.sources, h.reminders, h.engine, h.settings, access, h.clock, Backoff()) { UTC }

        assertThat(c.sync(SyncScope.CLOUD).outcomes[SourceType.MS_TODO]).isEqualTo(SourceOutcome.Synced)
    }
}
