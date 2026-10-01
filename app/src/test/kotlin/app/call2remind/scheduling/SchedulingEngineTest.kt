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
import app.call2remind.data.mapper.toEntity
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.data.repo.ReminderSnapshot
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeAlarmScheduler.Armed
import app.call2remind.testing.FakeRingContextProvider
import app.call2remind.testing.T0
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SchedulingEngineTest {
    private val h = EngineHarness()
    private val engine = h.engine
    private val clock = h.clock

    private val a = reminder("a", Schedule.At(T0.plus(hours(1))))
    private val b = reminder("b", Schedule.At(T0.plus(hours(3))))

    private fun occOf(reminder: Reminder, at: java.time.Instant): Occurrence = Occurrence.scheduled(reminder, at)

    /** Ring timeout (45 s) + recovery grace (30 s) + deadline slack (1 s). */
    private val unansweredDeadline: Duration = Duration.ofSeconds(45 + 30).plus(SchedulingEngine.DEADLINE_SLACK)

    /** Answered-call staleness (15 min) + deadline slack. */
    private val answeredDeadline: Duration = minutes(15).plus(SchedulingEngine.DEADLINE_SLACK)

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
        // Only the ringing row's deadline alarm remains.
        assertThat(h.alarms.armed.keys).containsExactly(ringing.id)
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
    fun claimNextTakesTheLockAndReArmsTheAlarmAtTheRingDeadline() = runBlocking<Unit> {
        val now = reminder("now", Schedule.At(T0))
        engine.upsertReminders(listOf(now))
        val occ = occOf(now, T0)
        clock.advance(Duration.ofSeconds(2))

        val step = engine.claimNext()

        assertThat(step).isEqualTo(RingStep.Ring(occ.copy(state = OccurrenceState.RINGING, fireAt = clock.now)))
        // The fired alarm is not left consumed: it now guards the ring (service death → recovery).
        assertThat(h.alarms.armed[occ.id]).isEqualTo(Armed(occ.id, clock.now.plus(unansweredDeadline), isSoonest = true))
        assertThat(h.alarms.cancelled).doesNotContain(occ.id)
        assertThat(h.occurrences.getRingLog(occ.id).map { it.type }).containsExactly(RingLogType.FIRED)

        // Reconcile derives the same deadline.
        h.alarms.reset()
        engine.reconcile()
        assertThat(h.alarms.armed[occ.id]?.at).isEqualTo(clock.now.plus(unansweredDeadline))
    }

    @Test
    fun answeringMovesTheDeadlineToTheAnsweredStaleness() = runBlocking<Unit> {
        engine.upsertReminders(listOf(reminder("now", Schedule.At(T0))))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        clock.advance(Duration.ofSeconds(5))

        engine.handle(ringing.id, OccurrenceEvent.Answer)

        assertThat(h.alarms.armed[ringing.id]?.at).isEqualTo(clock.now.plus(answeredDeadline))
        assertThat(h.alarms.cancelled).doesNotContain(ringing.id)
    }

    @Test
    fun orphanedRingIsRecoveredAtItsDeadlineAlarm() = runBlocking<Unit> {
        // The process dies right after claiming: no service, no ring timer, only the deadline alarm.
        engine.upsertReminders(listOf(reminder("now", Schedule.At(T0))))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        val deadline = requireNotNull(h.alarms.armed[ringing.id]).at

        clock.now = deadline
        val step = engine.claimNext() // the deadline alarm's receiver

        assertThat(step).isEqualTo(RingStep.Idle)
        val stored = requireNotNull(h.occurrences.get(ringing.id))
        assertThat(stored.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(stored.ringBacks).isEqualTo(1)
        assertThat(h.occurrences.getRingLog(ringing.id).last().reason).isEqualTo(RingLogEvent.REASON_RING_TIMEOUT)
        assertThat(h.missed.missed.map { it.id }).containsExactly(ringing.id)
        assertThat(h.alarms.armed[ringing.id]?.at).isEqualTo(deadline.plus(minutes(5)))
    }

    @Test
    fun alarmWhileAnotherOccurrenceRingsWithoutAServiceStillRingsAtTheDeadline() = runBlocking<Unit> {
        val first = reminder("first", Schedule.At(T0))
        val second = reminder("second", Schedule.At(T0.plus(Duration.ofSeconds(10))))
        engine.upsertReminders(listOf(first, second))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        val queued = occOf(second, T0.plus(Duration.ofSeconds(10)))

        // The second alarm fires while the first still "rings" (its service died): line busy.
        clock.now = T0.plus(Duration.ofSeconds(11))
        h.alarms.fire(queued.id)
        assertThat(engine.claimNext()).isEqualTo(RingStep.LineBusy)
        engine.reconcile()
        assertThat(h.alarms.armed).doesNotContainKey(queued.id)
        val deadline = requireNotNull(h.alarms.armed[ringing.id]).at
        assertThat(deadline).isEqualTo(T0.plus(unansweredDeadline))

        // The ringing row's deadline alarm frees the line and rings the queued occurrence.
        clock.now = deadline
        val step = engine.claimNext()

        assertThat((step as RingStep.Ring).occurrence.id).isEqualTo(queued.id)
        assertThat(h.occurrences.get(ringing.id)?.state).isEqualTo(OccurrenceState.SNOOZED)
    }

    @Test
    fun answeredAndAbandonedCallFreesTheLineAtItsDeadline() = runBlocking<Unit> {
        val first = reminder("first", Schedule.At(T0))
        val second = reminder("second", Schedule.At(T0.plus(minutes(1))))
        engine.upsertReminders(listOf(first, second))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        engine.handle(ringing.id, OccurrenceEvent.Answer)
        clock.now = T0.plus(minutes(1))
        assertThat(engine.claimNext()).isEqualTo(RingStep.LineBusy)
        engine.reconcile()

        val deadline = requireNotNull(h.alarms.armed[ringing.id]).at
        assertThat(deadline).isEqualTo(T0.plus(answeredDeadline))
        clock.now = deadline
        val step = engine.claimNext()

        assertThat(h.occurrences.get(ringing.id)?.state).isEqualTo(OccurrenceState.DONE)
        assertThat((step as RingStep.Ring).occurrence.id).isEqualTo(occOf(second, T0.plus(minutes(1))).id)
    }

    @Test
    fun endingARingArmsOccurrencesQueuedBehindItImmediately() = runBlocking<Unit> {
        val first = reminder("first", Schedule.At(T0))
        val second = reminder("second", Schedule.At(T0.plus(Duration.ofSeconds(20))))
        engine.upsertReminders(listOf(first, second))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        clock.now = T0.plus(Duration.ofSeconds(20))
        assertThat(engine.claimNext()).isEqualTo(RingStep.LineBusy)
        engine.reconcile()
        clock.now = T0.plus(Duration.ofSeconds(30))

        engine.handle(ringing.id, OccurrenceEvent.Done)

        val queued = occOf(second, T0.plus(Duration.ofSeconds(20)))
        assertThat(h.alarms.armed[queued.id]).isEqualTo(Armed(queued.id, clock.now, isSoonest = true))
        assertThat(h.alarms.armed).doesNotContainKey(ringing.id)
    }

    @Test
    fun deferGivesTheLineBackWithoutARingBackAndRingsAgain() = runBlocking<Unit> {
        engine.upsertReminders(listOf(reminder("now", Schedule.At(T0))))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence
        clock.advance(Duration.ofSeconds(3))

        engine.handle(ringing.id, OccurrenceEvent.Defer)

        val released = requireNotNull(h.occurrences.get(ringing.id))
        assertThat(released.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(released.ringBacks).isEqualTo(0)
        assertThat(h.missed.missed).isEmpty()
        assertThat(h.missed.ringEnded.map { it.id }).containsExactly(ringing.id)
        assertThat(h.alarms.armed[ringing.id]?.at).isEqualTo(clock.now)

        h.ringContext.context = FakeRingContextProvider.IDLE.copy(inRealCall = true)
        assertThat(engine.claimNext()).isInstanceOf(RingStep.Defer::class.java)
        h.ringContext.context = FakeRingContextProvider.IDLE
        assertThat((engine.claimNext() as RingStep.Ring).occurrence.id).isEqualTo(ringing.id)
    }

    @Test
    fun claimNextMarksTooLateRingsMissedBeforeRingingTheNextOne() = runBlocking<Unit> {
        val old = reminder("old", Schedule.At(T0.plus(hours(1))))
        engine.upsertReminders(listOf(old))
        clock.now = T0.plus(hours(3)).plus(minutes(30))
        val fresh = reminder("fresh", Schedule.At(clock.now))
        engine.upsertReminders(listOf(old, fresh))

        val step = engine.claimNext()

        assertThat((step as RingStep.Ring).occurrence.reminderId).isEqualTo(fresh.id)
        assertThat(h.occurrences.get(occOf(old, T0.plus(hours(1))).id)?.state).isEqualTo(OccurrenceState.MISSED)
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

    @Test
    fun corruptReminderRowKeepsItsOccurrences() = runBlocking<Unit> {
        engine.upsertReminders(listOf(a, b))
        val occA = occOf(a, T0.plus(hours(1)))
        // a's row becomes unreadable (e.g. a schedule format from a newer version).
        h.db.reminderDao().upsertAll(listOf(a.toEntity(null, T0).copy(scheduleJson = "{not json")))
        h.alarms.reset()

        val result = engine.replan(ReplanReason.MANUAL)

        assertThat(result.deleted).isEmpty()
        assertThat(h.alarms.cancelled).isEmpty()
        assertThat(h.occurrences.get(occA.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(h.alarms.armed.keys).containsExactly(occA.id, occOf(b, T0.plus(hours(3))).id)
    }

    @Test
    fun ringImmediatelyCannotBeCancelledByAConcurrentReplan() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // A replan that has read the reminders (before the reactive reminder exists) and then stalls.
        val stalling = object : ReminderRepository by h.reminders {
            override suspend fun getAllForPlanning(): ReminderSnapshot {
                val snapshot = h.reminders.getAllForPlanning()
                entered.complete(Unit)
                gate.await()
                return snapshot
            }
        }
        val engine = SchedulingEngine(stalling, h.occurrences, h.alarms, h.settings, h.ringContext, h.missed, clock)
        val samsung = reminder("s1", Schedule.At(T0), sourceType = SourceType.SAMSUNG_REMINDER)

        val replan = async(Dispatchers.Default) { engine.replan(ReplanReason.MANUAL) }
        entered.await()
        val ring = async(Dispatchers.Default) { engine.ringImmediately(samsung, T0, sourceId = "samsung") }
        delay(RACE_WINDOW_MS)
        gate.complete(Unit)
        replan.await()
        val occ = ring.await()

        assertThat(h.occurrences.get(occ.id)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(h.alarms.armed[occ.id]?.at).isEqualTo(T0)
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

    private companion object {
        const val RACE_WINDOW_MS = 300L
    }

    // --- device zone (DeviceZonePolicy) ---

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val losAngeles: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun daily(at: LocalTime) =
        Schedule.Recurring(RecurrenceRule(DayOfWeek.entries.toSet(), setOf(at), LocalDate.of(2026, 3, 1)))

    private suspend fun activeTimes(reminder: Reminder): List<Instant> =
        h.occurrences.getActive().filter { it.reminderId == reminder.id }.map { it.fireAt }.sorted()

    @Test
    fun zoneChangeMovesDeviceZoneRemindersToTheSameWallTimeInTheNewZone() = runBlocking<Unit> {
        clock.currentZone = kolkata
        val allDay = reminder("ev", Schedule.DateOnly(LocalDate.of(2026, 3, 11), LocalTime.of(9, 0)), SourceType.CALENDAR, zone = kolkata)
        val habit = reminder("walk", daily(LocalTime.of(9, 0)), zone = kolkata)
        val todo = reminder("todo", Schedule.DateOnly(LocalDate.of(2026, 3, 11), LocalTime.of(9, 0)), SourceType.MS_TODO, zone = kolkata)
        engine.upsertReminders(listOf(allDay, habit, todo))
        // 09:00 IST = 03:30Z.
        val oldAllDay = occOf(allDay, Instant.parse("2026-03-11T03:30:00Z"))
        assertThat(activeTimes(allDay)).containsExactly(oldAllDay.fireAt)
        assertThat(activeTimes(habit)).containsExactly(Instant.parse("2026-03-11T03:30:00Z"), Instant.parse("2026-03-12T03:30:00Z"))

        clock.currentZone = losAngeles
        engine.onTimeChanged()

        // 09:00 PDT = 16:00Z; the window ends at T0 + 48 h = 2026-03-12T08:00Z.
        val newAllDay = occOf(allDay.copy(zone = losAngeles), Instant.parse("2026-03-11T16:00:00Z"))
        assertThat(activeTimes(allDay)).containsExactly(newAllDay.fireAt)
        assertThat(activeTimes(habit))
            .containsExactly(Instant.parse("2026-03-10T16:00:00Z"), Instant.parse("2026-03-11T16:00:00Z")).inOrder()
        // MS To Do carries its own zone: unchanged.
        assertThat(activeTimes(todo)).containsExactly(Instant.parse("2026-03-11T03:30:00Z"))
        assertThat(h.reminders.get(allDay.id)?.zone).isEqualTo(losAngeles)
        assertThat(h.reminders.get(habit.id)?.zone).isEqualTo(losAngeles)
        assertThat(h.reminders.get(todo.id)?.zone).isEqualTo(kolkata)
        // Alarms follow: the old instants are cancelled, the new ones armed.
        assertThat(h.alarms.cancelled).contains(oldAllDay.id)
        assertThat(h.alarms.armed[newAllDay.id]?.at).isEqualTo(newAllDay.fireAt)
        assertThat(h.alarms.armed).doesNotContainKey(oldAllDay.id)
    }

    @Test
    fun zoneChangeKeepsSnoozedAndRingingHabitOccurrences() = runBlocking<Unit> {
        clock.currentZone = kolkata
        // 13:30 IST = 08:00Z = T0: both due now.
        val first = reminder("first", daily(LocalTime.of(13, 30)), zone = kolkata)
        val second = reminder("second", daily(LocalTime.of(13, 30)), zone = kolkata)
        engine.upsertReminders(listOf(first, second))
        val snoozed = (engine.claimNext() as RingStep.Ring).occurrence
        engine.handle(snoozed.id, OccurrenceEvent.Snooze(minutes(10)))
        val ringing = (engine.claimNext() as RingStep.Ring).occurrence

        clock.currentZone = losAngeles
        engine.onTimeChanged()

        val snoozedNow = h.occurrences.get(snoozed.id)
        assertThat(snoozedNow?.state).isEqualTo(OccurrenceState.SNOOZED)
        assertThat(snoozedNow?.fireAt).isEqualTo(T0.plus(minutes(10)))
        assertThat(h.occurrences.get(ringing.id)?.state).isEqualTo(OccurrenceState.RINGING)
        // Later rings move to 13:30 PDT (20:30Z).
        for (r in listOf(first, second)) {
            assertThat(activeTimes(r)).containsAtLeast(Instant.parse("2026-03-10T20:30:00Z"), Instant.parse("2026-03-11T20:30:00Z"))
            assertThat(activeTimes(r)).doesNotContain(Instant.parse("2026-03-11T08:00:00Z"))
        }
    }

    @Test
    fun replanAfterASyncThatStillUsedTheOldZoneConvergesOnTheDeviceZone() = runBlocking<Unit> {
        // The zone already changed, but a sync that read the old zone stores its reminders late.
        clock.currentZone = losAngeles
        val allDay = reminder("ev", Schedule.DateOnly(LocalDate.of(2026, 3, 11), LocalTime.of(9, 0)), SourceType.GOOGLE_TASKS, zone = kolkata)
        h.reminders.upsertAll(listOf(allDay), sourceId = "tasks")

        engine.replan(ReplanReason.SYNC)

        assertThat(activeTimes(allDay)).containsExactly(Instant.parse("2026-03-11T16:00:00Z"))
        assertThat(h.reminders.get(allDay.id)?.zone).isEqualTo(losAngeles)
        // Nothing left to move: a second replan writes nothing and changes nothing.
        val again = engine.replan(ReplanReason.SYNC)
        assertThat(again.created).isEmpty()
        assertThat(again.deleted).isEmpty()
    }
}
