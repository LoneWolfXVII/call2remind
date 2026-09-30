package app.call2remind.data.repo

import app.cash.turbine.test
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.planning.CancelReason
import app.call2remind.core.planning.Cancellation
import app.call2remind.core.planning.Plan
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.TransitionResult
import app.call2remind.settings.Settings
import app.call2remind.testing.FakeSettingsRepository
import app.call2remind.testing.MutableClock
import app.call2remind.testing.T0
import app.call2remind.testing.awaitItemMatching
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.newTestDb
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class OccurrenceRepositoryTest {
    private val clock = MutableClock()
    private val settings = FakeSettingsRepository()
    private val db = newTestDb()
    private lateinit var repo: RoomOccurrenceRepository
    private lateinit var reminders: RoomReminderRepository

    private val a = reminder("a", Schedule.At(T0))
    private val b = reminder("b", Schedule.At(T0.plus(hours(1))))

    @Before
    fun setUp() {
        repo = RoomOccurrenceRepository(db, settings, clock)
        reminders = RoomReminderRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(vararg occurrences: Occurrence) {
        occurrences.forEach { assertThat(repo.insertIfAbsent(it)).isTrue() }
    }

    // --- transition ---

    @Test
    fun transitionWritesStateAndRingLogTogether() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        insert(occ)
        assertThat(repo.tryClaimRing(occ.id, T0)).isTrue()
        clock.advance(Duration.ofSeconds(10))

        val result = repo.transition(occ.id, OccurrenceEvent.Decline)

        assertThat(result).isInstanceOf(TransitionResult.Transitioned::class.java)
        val stored = requireNotNull(repo.get(occ.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(stored.fireAt).isEqualTo(clock.now.plus(minutes(5)))
        assertThat(stored.ringBacks).isEqualTo(1)
        assertThat(stored).isEqualTo((result as TransitionResult.Transitioned).occurrence)
        val log = repo.getRingLog(occ.id)
        assertThat(log.map { it.type }).containsExactly(RingLogType.FIRED, RingLogType.SNOOZED).inOrder()
        assertThat(log.last().reason).isEqualTo(RingLogEvent.REASON_DECLINED)
        assertThat(log.last().timestamp).isEqualTo(clock.now)
    }

    @Test
    fun transitionUsesTheCurrentSnoozeSettings() = runBlocking<Unit> {
        settings.state.value = Settings(snoozeLength = minutes(12), maxRingBacks = 1)
        val occ = occurrence(a, T0)
        insert(occ)
        repo.tryClaimRing(occ.id, T0)

        repo.transition(occ.id, OccurrenceEvent.RingTimeout)
        assertThat(repo.get(occ.id)?.fireAt).isEqualTo(T0.plus(minutes(12)))

        clock.now = T0.plus(minutes(12))
        assertThat(repo.tryClaimRing(occ.id, clock.now)).isTrue()
        repo.transition(occ.id, OccurrenceEvent.Decline)

        val stored = requireNotNull(repo.get(occ.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.MISSED)
        assertThat(repo.getRingLog(occ.id).last().reason).isEqualTo(RingLogEvent.REASON_MAX_RING_BACKS)
    }

    @Test
    fun invalidTransitionChangesNothing() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        insert(occ)

        val result = repo.transition(occ.id, OccurrenceEvent.Answer)

        assertThat(result).isEqualTo(TransitionResult.InvalidTransition(OccurrenceState.SCHEDULED, OccurrenceEvent.Answer))
        assertThat(repo.get(occ.id)).isEqualTo(occ)
        assertThat(repo.getRingLog(occ.id)).isEmpty()
    }

    @Test
    fun transitionOfUnknownOccurrenceReturnsNull() = runBlocking<Unit> {
        assertThat(repo.transition("nope", OccurrenceEvent.Done)).isNull()
    }

    // --- tryClaimRing ---

    @Test
    fun claimMovesDueOccurrenceToRingingAndLogsFiredOnce() = runBlocking<Unit> {
        val occ = occurrence(a, T0, answeredAt = null)
        insert(occ)
        val now = T0.plusSeconds(3)

        assertThat(repo.tryClaimRing(occ.id, now)).isTrue()
        assertThat(repo.tryClaimRing(occ.id, now)).isFalse()

        val stored = requireNotNull(repo.get(occ.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(stored.fireAt).isEqualTo(now)
        assertThat(stored.answeredAt).isNull()
        assertThat(stored.plannedAt).isEqualTo(T0)
        assertThat(repo.getRingLog(occ.id)).containsExactly(RingLogEvent(occ.id, RingLogType.FIRED, now))
    }

    @Test
    fun claimRefusesOccurrencesThatAreNotDue() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        insert(occ)

        assertThat(repo.tryClaimRing(occ.id, T0.minusMillis(1))).isFalse()
        assertThat(repo.get(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(repo.getRingLog(occ.id)).isEmpty()
    }

    @Test
    fun claimAcceptsDueSnoozedOccurrencesAndKeepsRingBacks() = runBlocking<Unit> {
        val occ = occurrence(a, T0, state = OccurrenceState.SNOOZED, fireAt = T0.plus(minutes(5)), ringBacks = 2)
        insert(occ)

        assertThat(repo.tryClaimRing(occ.id, T0.plus(minutes(5)))).isTrue()

        val stored = requireNotNull(repo.get(occ.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(stored.ringBacks).isEqualTo(2)
    }

    @Test
    fun claimRefusesTerminalAndUnknownOccurrences() = runBlocking<Unit> {
        val done = occurrence(a, T0, state = OccurrenceState.DONE)
        val missed = occurrence(b, T0, state = OccurrenceState.MISSED)
        insert(done, missed)

        assertThat(repo.tryClaimRing(done.id, T0)).isFalse()
        assertThat(repo.tryClaimRing(missed.id, T0)).isFalse()
        assertThat(repo.tryClaimRing("unknown", T0)).isFalse()
    }

    @Test
    fun claimRefusesWhileAnotherOccurrenceIsRinging() = runBlocking<Unit> {
        val ringing = occurrence(a, T0, state = OccurrenceState.RINGING)
        val due = occurrence(b, T0)
        insert(ringing, due)

        assertThat(repo.tryClaimRing(due.id, T0)).isFalse()
        assertThat(repo.get(due.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)

        repo.transition(ringing.id, OccurrenceEvent.Done)
        assertThat(repo.tryClaimRing(due.id, T0)).isTrue()
    }

    @Test
    fun concurrentClaimsOfTheSameOccurrenceSucceedExactlyOnce() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        insert(occ)

        val results = (1..32).map { async(Dispatchers.IO) { repo.tryClaimRing(occ.id, T0) } }.awaitAll()

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(repo.getRingLog(occ.id).filter { it.type == RingLogType.FIRED }).hasSize(1)
    }

    @Test
    fun concurrentClaimsOfDifferentDueOccurrencesRingOnlyOne() = runBlocking<Unit> {
        val due = (1..12).map { occurrence(reminder("r$it", Schedule.At(T0)), T0) }
        insert(*due.toTypedArray())

        val results = due.map { occ -> async(Dispatchers.IO) { repo.tryClaimRing(occ.id, T0) } }.awaitAll()

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(repo.getActive().count { it.state == OccurrenceState.RINGING }).isEqualTo(1)
    }

    // --- applyPlan & queries ---

    @Test
    fun applyPlanDeletesPendingCancellationsRepointsAndInsertsNewRows() = runBlocking<Unit> {
        val pending = occurrence(a, T0)
        val snoozed = occurrence(b, T0, state = OccurrenceState.SNOOZED)
        val ringing = occurrence(reminder("c"), T0, state = OccurrenceState.RINGING)
        val kept = occurrence(reminder("d"), T0)
        insert(pending, snoozed, ringing, kept)
        val fresh = occurrence(reminder("e"), T0.plus(hours(2)))

        val applied = repo.applyPlan(
            Plan(
                toCreate = listOf(fresh, kept),
                toCancel = listOf(pending, snoozed, ringing).map { Cancellation(it, CancelReason.REMINDER_REMOVED) },
                toKeep = emptyList(),
                toUpdate = listOf(kept.copy(reminderId = "new-owner")),
            ),
        )

        assertThat(applied.created).containsExactly(fresh)
        assertThat(applied.deleted.map { it.id }).containsExactly(pending.id, snoozed.id)
        assertThat(repo.get(pending.id)).isNull()
        assertThat(repo.get(snoozed.id)).isNull()
        assertThat(repo.get(ringing.id)?.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(repo.get(kept.id)?.reminderId).isEqualTo("new-owner")
        assertThat(repo.get(fresh.id)).isEqualTo(fresh)
    }

    @Test
    fun applyPlanDeletesBeforeInsertingTheSameId() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        insert(occ)
        val replacement = occ.copy(reminderId = "twin")

        val applied = repo.applyPlan(
            Plan(listOf(replacement), listOf(Cancellation(occ, CancelReason.REMINDER_DISABLED)), emptyList()),
        )

        assertThat(applied.created).containsExactly(replacement)
        assertThat(repo.get(occ.id)?.reminderId).isEqualTo("twin")
    }

    @Test
    fun insertIfAbsentNeverOverwrites() = runBlocking<Unit> {
        val occ = occurrence(a, T0)
        assertThat(repo.insertIfAbsent(occ)).isTrue()
        assertThat(repo.insertIfAbsent(occ.copy(state = OccurrenceState.DONE))).isFalse()
        assertThat(repo.get(occ.id)).isEqualTo(occ)
    }

    @Test
    fun queriesFilterByStateAndOrderByFireTime() = runBlocking<Unit> {
        val later = occurrence(a, T0.plus(hours(2)))
        val soon = occurrence(b, T0.plus(hours(1)))
        val ringing = occurrence(reminder("c"), T0, state = OccurrenceState.RINGING)
        val oldDone = occurrence(reminder("d"), T0.minus(hours(5)), state = OccurrenceState.DONE)
        val recentSkipped = occurrence(reminder("e"), T0.minus(hours(1)), state = OccurrenceState.SKIPPED)
        insert(later, soon, ringing, oldDone, recentSkipped)

        assertThat(repo.getActive().map { it.id }).containsExactly(ringing.id, soon.id, later.id).inOrder()
        assertThat(repo.getPending().map { it.id }).containsExactly(soon.id, later.id).inOrder()
        assertThat(repo.getForPlanning(T0.minus(hours(2))).map { it.id })
            .containsExactly(ringing.id, soon.id, later.id, recentSkipped.id)
    }

    @Test
    fun observeUpcomingJoinsRemindersAndUpdatesLive() = runBlocking<Unit> {
        reminders.upsert(a)
        repo.observeUpcoming().test {
            assertThat(awaitItem()).isEmpty()

            val occ = occurrence(a, T0)
            repo.insertIfAbsent(occ)
            val rows = awaitItemMatching { it.isNotEmpty() }
            assertThat(rows.single().occurrence).isEqualTo(occ)
            assertThat(rows.single().reminder).isEqualTo(a)

            repo.transition(occ.id, OccurrenceEvent.Done)
            awaitItemMatching { it.isEmpty() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeHistoryListsTerminalOccurrencesNewestFirst() = runBlocking<Unit> {
        val older = occurrence(a, T0.minus(hours(3)), state = OccurrenceState.MISSED)
        val newer = occurrence(b, T0.minus(hours(1)), state = OccurrenceState.DONE)
        insert(older, newer, occurrence(reminder("c"), T0))

        repo.observeHistory().test {
            val rows = awaitItem()
            assertThat(rows.map { it.occurrence.id }).containsExactly(newer.id, older.id).inOrder()
            assertThat(rows.map { it.reminder }).containsExactly(null, null)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ringLogIsObservableAndAppendable() = runBlocking<Unit> {
        repo.appendLog(RingLogEvent("x", RingLogType.DEFERRED, T0, RingLogEvent.REASON_IN_CALL))
        repo.appendLog(RingLogEvent("x", RingLogType.FAILED, T0.plusSeconds(1)))

        repo.observeRingLog().test {
            assertThat(awaitItem().map { it.type }).containsExactly(RingLogType.FAILED, RingLogType.DEFERRED).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.getRingLog("x").first().reason).isEqualTo(RingLogEvent.REASON_IN_CALL)
    }

    @Test
    fun pruneHistoryDropsOnlyOldTerminalRowsAndLogs() = runBlocking<Unit> {
        val oldDone = occurrence(a, T0.minus(Duration.ofDays(40)), state = OccurrenceState.DONE)
        val recentDone = occurrence(b, T0.minus(Duration.ofDays(1)), state = OccurrenceState.DONE)
        val oldPending = occurrence(reminder("c"), T0.minus(Duration.ofDays(40)))
        insert(oldDone, recentDone, oldPending)
        repo.appendLog(RingLogEvent(oldDone.id, RingLogType.DONE, T0.minus(Duration.ofDays(40))))
        repo.appendLog(RingLogEvent(recentDone.id, RingLogType.DONE, T0.minus(Duration.ofDays(1))))

        val pruned = repo.pruneHistory(T0.minus(Duration.ofDays(30)))

        assertThat(pruned).isEqualTo(2)
        assertThat(repo.get(oldDone.id)).isNull()
        assertThat(repo.get(recentDone.id)).isNotNull()
        assertThat(repo.get(oldPending.id)).isNotNull()
        assertThat(repo.getRingLog(oldDone.id)).isEmpty()
        assertThat(repo.getRingLog(recentDone.id)).hasSize(1)
    }
}
