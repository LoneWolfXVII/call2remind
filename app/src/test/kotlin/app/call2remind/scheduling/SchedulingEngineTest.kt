package app.call2remind.scheduling

import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.log.RingLogType
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.RecoveryAction
import app.call2remind.core.ringing.TransitionResult
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeAlarmScheduler.Armed
import app.call2remind.testing.FakeRingContextProvider
import app.call2remind.testing.T0
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SchedulingEngineTest {
    private val h = EngineHarness()
    private val engine = h.engine
    private val clock = h.clock

    private val a = reminder("a", Schedule.At(T0.plus(hours(1))))
    private val b = reminder("b", Schedule.At(T0.plus(hours(3))))

    private fun occOf(reminder: Reminder, at: java.time.Instant): Occurrence = Occurrence.scheduled(reminder, at)

    @After
    fun tearDown() = h.close()

    // --- replan / reconcile ---

    @Test
    fun replanCreatesOccurrencesAndArmsTheSoonestAsAlarmClock() = runBlocking<Unit> {
        val result = engine.upsertReminders(listOf(b, a))

        val occA = occOf(a, T0.plus(hours(1)))
        val occB = occOf(b, T0.plus(hours(3)))
        assertThat(result.reason).isEqualTo(ReplanReason.REMINDER_CHANGED)
        assertThat(result.created).containsExactly(occA, occB).inOrder()
        assertThat(result.armed).isEqualTo(2)
        assertThat(h.alarms.armed).containsExactly(
            occA.id, Armed(occA.id, T0.plus(hours(1)), isSoonest = true),
            occB.id, Armed(occB.id, T0.plus(hours(3)), isSoonest = false),
        )
        assertThat(h.occurrences.getPending()).containsExactly(occA, occB).inOrder()
    }

    @Test
    fun replanExpandsRecurringHabitsOverThe48HourWindow() = runBlocking<Unit> {
        val habit = reminder(
            "habit",
            Schedule.Recurring(RecurrenceRule(DayOfWeek.entries.toSet(), setOf(LocalTime.of(9, 0)), LocalDate.of(2026, 3, 1))),
        )

        val result = engine.upsertReminders(listOf(habit))

        assertThat(result.created.map { it.fireAt })
            .containsExactly(T0.plus(hours(1)), T0.plus(hours(25))).inOrder()
    }

    @Test
    fun replanIsIdempotent() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        val before = h.occurrences.getActive()

        val again = engine.replan(ReplanReason.MANUAL)
        val third = engine.replan(ReplanReason.APP_START)

        assertThat(again.created).isEmpty()
        assertThat(again.deleted).isEmpty()
        assertThat(third.created).isEmpty()
        assertThat(h.occurrences.getActive()).isEqualTo(before)
        assertThat(h.alarms.armed).hasSize(2)
        assertThat(h.alarms.cancelled).isEmpty()
    }

    @Test
    fun disablingAReminderDeletesItsPendingOccurrenceAndCancelsTheAlarm() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        val occA = occOf(a, T0.plus(hours(1)))

        val result = engine.upsertReminders(listOf(a.copy(enabled = false)))

        assertThat(result.deleted).containsExactly(occA)
        assertThat(h.occurrences.get(occA.id)).isNull()
        assertThat(h.alarms.cancelled).containsExactly(occA.id)
        assertThat(h.alarms.armed.keys).containsExactly(occOf(b, T0.plus(hours(3))).id)
        assertThat(h.alarms.armed.values.single().isSoonest).isTrue()
    }

    @Test
    fun rescheduledReminderReplacesItsFutureOccurrence() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val old = occOf(a, T0.plus(hours(1)))
        val moved = a.copy(schedule = Schedule.At(T0.plus(hours(2))))

        val result = engine.upsertReminders(listOf(moved))

        val new = occOf(moved, T0.plus(hours(2)))
        assertThat(result.deleted).containsExactly(old)
        assertThat(result.created).containsExactly(new)
        assertThat(h.alarms.cancelled).contains(old.id)
        assertThat(h.alarms.armed.keys).containsExactly(new.id)
    }

    @Test
    fun rescheduleKeepsASnoozedOccurrence() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val occA = occOf(a, T0.plus(hours(1)))
        clock.now = T0.plus(hours(1))
        assertThat(engine.claimNext()).isInstanceOf(RingStep.Ring::class.java)
        engine.handle(occA.id, OccurrenceEvent.Decline)

        engine.upsertReminders(listOf(a.copy(schedule = Schedule.At(T0.plus(hours(5))))))

        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(h.alarms.armed[occA.id]?.at).isEqualTo(T0.plus(hours(1)).plus(minutes(5)))
    }

    @Test
    fun deletingRemindersRemovesPendingRowsButNeverTheRingingOne() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now, b))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence

        val result = engine.deleteReminders(listOf(now.id, b.id))

        assertThat(result.deleted.map { it.id }).containsExactly(occOf(b, T0.plus(hours(3))).id)
        assertThat(h.occurrences.get(ringing.id)?.state).isEqualTo(OccurrenceState.RINGING)
        assertThat(h.alarms.armed).isEmpty()
    }

    @Test
    fun reconcileReArmsEveryPendingAlarm() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        h.alarms.reset()

        assertThat(engine.reconcile()).isEqualTo(2)
        assertThat(h.alarms.armed).hasSize(2)
        assertThat(engine.reconcile()).isEqualTo(2)
        assertThat(h.alarms.armed).hasSize(2)
    }

    @Test
    fun reconcileArmsOverdueOccurrencesOnlyWhenAsked() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        clock.now = T0.plus(hours(1)).plus(minutes(1))
        h.alarms.reset()

        assertThat(engine.reconcile()).isEqualTo(0)
        assertThat(engine.reconcile(armOverdue = true)).isEqualTo(1)

        assertThat(h.alarms.armed.values.single()).isEqualTo(Armed(occOf(a, T0.plus(hours(1))).id, clock.now, true))
    }

    @Test
    fun reconcileCapsTheNumberOfArmedAlarms() = runBlocking<Unit> {
        val many = (1..SchedulingEngine.MAX_ARMED_ALARMS + 5).map { reminder("m$it", Schedule.At(T0.plus(minutes(it.toLong())))) }

        engine.upsertReminders(many)

        assertThat(h.alarms.armed).hasSize(SchedulingEngine.MAX_ARMED_ALARMS)
        assertThat(h.alarms.armed.values.filter { it.isSoonest }.map { it.at }).containsExactly(T0.plus(minutes(1)))
    }

    @Test
    fun defaultTimeChangeMovesDateOnlyRings() = runBlocking<Unit> {
        val task = reminder("t", Schedule.DateOnly(LocalDate.of(2026, 3, 10)), sourceType = SourceType.GOOGLE_TASKS)
        engine.upsertReminders(listOf(task))
        assertThat(h.occurrences.getPending().single().fireAt).isEqualTo(T0.plus(hours(1)))

        h.settings.update { it.copy(defaultTimes = it.defaultTimes.with(SourceType.GOOGLE_TASKS, LocalTime.of(10, 0))) }
        val result = engine.replan(ReplanReason.SETTINGS_CHANGED)

        assertThat(result.deleted.map { it.fireAt }).containsExactly(T0.plus(hours(1)))
        assertThat(result.created.map { it.fireAt }).containsExactly(T0.plus(hours(2)))
        assertThat(h.alarms.armed.values.single().at).isEqualTo(T0.plus(hours(2)))
    }

    // --- recovery ---

    @Test
    fun bootRecoveryRingsRecentMissesImmediately() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val occA = occOf(a, T0.plus(hours(1)))
        clock.now = T0.plus(hours(1)).plus(minutes(30))
        h.alarms.reset()

        val actions = engine.recoverAfterBoot()

        assertThat(actions).containsExactly(RecoveryAction.RingNow(occA, minutes(30)))
        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(h.alarms.armed[occA.id]).isEqualTo(Armed(occA.id, clock.now, isSoonest = true))
    }

    @Test
    fun bootRecoveryMarksOldMissesMissed() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val occA = occOf(a, T0.plus(hours(1)))
        clock.now = T0.plus(hours(4))
        h.alarms.reset()

        val actions = engine.recoverAfterBoot()

        assertThat(actions).containsExactly(RecoveryAction.MarkMissed(occA, hours(3)))
        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.MISSED)
        assertThat(h.missed.missed.map { it.id }).containsExactly(occA.id)
        assertThat(h.occurrences.getRingLog(occA.id).map { it.type }).containsExactly(RingLogType.MISSED)
        assertThat(h.alarms.armed).isEmpty()
    }

    @Test
    fun bootLookbackCreatesRingsMissedWhileThePhoneWasOff() = runBlocking<Unit> {
        h.reminders.upsert(a)
        clock.now = T0.plus(hours(1)).plus(minutes(20))

        assertThat(engine.replan(ReplanReason.APP_START).created).isEmpty()

        val actions = engine.recoverAfterBoot()

        val occA = occOf(a, T0.plus(hours(1)))
        assertThat(actions).containsExactly(RecoveryAction.RingNow(occA, minutes(20)))
        assertThat(h.alarms.armed[occA.id]?.at).isEqualTo(clock.now)
    }

    @Test
    fun watchdogTimesOutStuckRings() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        clock.advance(minutes(2))

        val actions = engine.runWatchdog()

        assertThat(actions.single()).isInstanceOf(RecoveryAction.TimeOutRinging::class.java)
        val stored = requireNotNull(h.occurrences.get(ringing.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(stored.ringBacks).isEqualTo(1)
        assertThat(h.missed.missed.map { it.id }).containsExactly(ringing.id)
        assertThat(h.missed.ringEnded.map { it.id }).containsExactly(ringing.id)
        assertThat(h.alarms.armed[ringing.id]?.at).isEqualTo(clock.now.plus(minutes(5)))
    }

    @Test
    fun watchdogFinishesStaleAnsweredCalls() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        engine.handle(ringing.id, OccurrenceEvent.Answer)
        clock.advance(minutes(16))

        engine.runWatchdog()

        assertThat(h.occurrences.get(ringing.id)?.state).isEqualTo(OccurrenceState.DONE)
        assertThat(h.missed.resolved.map { it.id }).containsExactly(ringing.id)
    }

    @Test
    fun appStartAndTimeChangeRecoverRingsTheClockJumpedOver() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        val occA = occOf(a, T0.plus(hours(1)))
        val occB = occOf(b, T0.plus(hours(3)))
        clock.now = T0.plus(hours(1)).plus(minutes(10))
        h.alarms.reset()

        val timeChange = engine.onTimeChanged()

        assertThat(timeChange.reason).isEqualTo(ReplanReason.TIME_CHANGE)
        assertThat(h.alarms.armed[occA.id]).isEqualTo(Armed(occA.id, clock.now, isSoonest = true))
        assertThat(h.alarms.armed[occB.id]?.at).isEqualTo(T0.plus(hours(3)))

        clock.now = T0.plus(hours(3)).plus(hours(2))
        val appStart = engine.onAppStart()

        assertThat(appStart.reason).isEqualTo(ReplanReason.APP_START)
        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.MISSED)
        assertThat(h.occurrences.get(occB.id)?.state).isEqualTo(OccurrenceState.MISSED)
    }

    // --- ring queue ---

    @Test
    fun claimNextIsIdleWhenNothingIsDue() = runBlocking<Unit> {
        assertThat(engine.claimNext()).isEqualTo(RingStep.Idle)
        engine.upsertReminders(listOf(a))
        assertThat(engine.claimNext()).isEqualTo(RingStep.Idle)
    }

    @Test
    fun claimNextTakesTheLockAndCancelsTheAlarm() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        val occ = occOf(now, T0)
        clock.advance(Duration.ofSeconds(2))

        val step = engine.claimNext()

        assertThat(step).isEqualTo(RingStep.Ring(occ.copy(state = OccurrenceState.RINGING, fireAt = clock.now)))
        assertThat(h.alarms.cancelled).contains(occ.id)
        assertThat(h.alarms.armed).doesNotContainKey(occ.id)
        assertThat(h.occurrences.getRingLog(occ.id).map { it.type }).containsExactly(RingLogType.FIRED)
    }

    @Test
    fun claimNextRingsOneAtATimeInQueueOrder() = runBlocking<Unit> {
        val habit = reminder("hab", Schedule.At(T0), sourceType = SourceType.HABIT)
        val meeting = reminder("cal", Schedule.At(T0), sourceType = SourceType.CALENDAR)
        engine.upsertReminders(listOf(habit, meeting))

        val first = engine.claimNext()
        val second = engine.claimNext()

        assertThat((first as RingStep.Ring).occurrence.reminderId).isEqualTo(meeting.id)
        assertThat(second).isEqualTo(RingStep.LineBusy)

        engine.handle(first.occurrence.id, OccurrenceEvent.Done)
        assertThat((engine.claimNext() as RingStep.Ring).occurrence.reminderId).isEqualTo(habit.id)
    }

    @Test
    fun claimNextDefersWhileTheUserIsInACall() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        h.ringContext.context = FakeRingContextProvider.IDLE.copy(inRealCall = true)

        val step = engine.claimNext()

        val occ = occOf(now, T0)
        assertThat(step).isEqualTo(RingStep.Defer(listOf(occ)))
        assertThat(h.occurrences.get(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(h.occurrences.getRingLog(occ.id))
            .containsExactly(RingLogEvent(occ.id, RingLogType.DEFERRED, T0, RingLogEvent.REASON_IN_CALL))

        h.ringContext.context = FakeRingContextProvider.IDLE
        assertThat(engine.claimNext()).isInstanceOf(RingStep.Ring::class.java)
    }

    // --- handle ---

    @Test
    fun snoozeArmsANewAlarmAtTheSnoozeTime() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        engine.handle(ringing.id, OccurrenceEvent.Answer)

        val result = engine.handle(ringing.id, OccurrenceEvent.Snooze(minutes(10)))

        assertThat((result as TransitionResult.Transitioned).state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(h.alarms.armed[ringing.id]).isEqualTo(Armed(ringing.id, T0.plus(minutes(10)), isSoonest = true))
        assertThat(h.missed.ringEnded.map { it.id }).containsExactly(ringing.id)
        assertThat(h.missed.missed).isEmpty()
    }

    @Test
    fun doneAndSkipCancelTheAlarmAndResolveMissedNotices() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        val occA = occOf(a, T0.plus(hours(1)))
        val occB = occOf(b, T0.plus(hours(3)))

        engine.handle(occA.id, OccurrenceEvent.Done)
        engine.handle(occB.id, OccurrenceEvent.Skip)

        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.DONE)
        assertThat(h.occurrences.get(occB.id)?.state).isEqualTo(OccurrenceState.SKIPPED)
        assertThat(h.alarms.cancelled).containsExactly(occA.id, occB.id).inOrder()
        assertThat(h.alarms.armed).isEmpty()
        assertThat(h.missed.resolved.map { it.id }).containsExactly(occA.id, occB.id).inOrder()
        assertThat(h.missed.ringEnded).isEmpty()
        // A finished occurrence is never planned again.
        assertThat(engine.replan(ReplanReason.MANUAL).created).isEmpty()
    }

    @Test
    fun markMissedNotifies() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val occA = occOf(a, T0.plus(hours(1)))

        engine.handle(occA.id, OccurrenceEvent.MarkMissed)

        assertThat(h.missed.missed.map { it.state }).containsExactly(OccurrenceState.MISSED)
    }

    @Test
    fun invalidAndUnknownEventsChangeNothing() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a))
        val occA = occOf(a, T0.plus(hours(1)))
        val armCalls = h.alarms.armCalls.size

        assertThat(engine.handle("missing", OccurrenceEvent.Done)).isNull()
        assertThat(engine.handle(occA.id, OccurrenceEvent.Decline))
            .isEqualTo(TransitionResult.InvalidTransition(OccurrenceState.SCHEDULED, OccurrenceEvent.Decline))
        assertThat(h.alarms.armCalls).hasSize(armCalls)
        assertThat(h.alarms.cancelled).isEmpty()
    }

    // --- sources ---

    @Test
    fun sourceSnapshotReplacesTheSourcesRemindersAndTheirRings() = runBlocking<Unit> {
        val first = engine.applySourceSnapshot("tasks", listOf(a, b))
        assertThat(first.reason).isEqualTo(ReplanReason.SYNC)
        assertThat(first.created).hasSize(2)

        val second = engine.applySourceSnapshot("tasks", listOf(b))

        val occA = occOf(a, T0.plus(hours(1)))
        assertThat(second.deleted.map { it.id }).containsExactly(occA.id)
        assertThat(h.reminders.getAll().map { it.id }).containsExactly(b.id)
        assertThat(h.alarms.cancelled).containsExactly(occA.id)
    }

    @Test
    fun ringImmediatelyStoresAndArmsOnceWithoutDoubleRinging() = runBlocking<Unit> {
        val samsung = reminder("s1", Schedule.At(T0), sourceType = SourceType.SAMSUNG_REMINDER)

        val occ = engine.ringImmediately(samsung, T0, sourceId = "samsung")
        val again = engine.ringImmediately(samsung, T0, sourceId = "samsung")

        assertThat(occ).isEqualTo(occOf(samsung, T0))
        assertThat(again).isEqualTo(occ)
        assertThat(h.occurrences.getActive()).containsExactly(occ)
        assertThat(h.alarms.armed[occ.id]).isEqualTo(Armed(occ.id, T0, isSoonest = true))

        engine.claimNext()
        val armCalls = h.alarms.armCalls.size
        assertThat(engine.ringImmediately(samsung, T0).state).isEqualTo(OccurrenceState.RINGING)
        assertThat(h.alarms.armCalls).hasSize(armCalls)
        // A later replan keeps the reactive occurrence.
        assertThat(engine.replan(ReplanReason.MANUAL).deleted).isEmpty()
    }

    @Test
    fun dailyTopUpPrunesOldHistory() = runBlocking<Unit> {
        val old = occurrence(a, T0.minus(Duration.ofDays(40)), state = OccurrenceState.DONE)
        h.occurrences.insertIfAbsent(old)

        val result = engine.dailyTopUp()

        assertThat(result.reason).isEqualTo(ReplanReason.DAILY_TOP_UP)
        assertThat(h.occurrences.get(old.id)).isNull()
    }
}
