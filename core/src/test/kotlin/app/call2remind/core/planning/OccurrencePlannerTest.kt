package app.call2remind.core.planning

import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.fixedClock
import app.call2remind.core.Fixtures.reminder
import app.call2remind.core.Fixtures.time
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceKey
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.core.time.DefaultTimes
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.MonthDay

class OccurrencePlannerTest {

    private fun ist(localDateTime: String) = at(localDateTime, 5, 30)

    private val now = ist("2026-10-01T10:00")
    private val planner = OccurrencePlanner(fixedClock(now))

    private val habit = reminder(
        id = "habit",
        sourceType = SourceType.HABIT,
        schedule = Schedule.Recurring(
            RecurrenceRule(DayOfWeek.entries.toSet(), setOf(time("09:00"), time("21:00")), date("2026-09-01")),
        ),
    )
    private val taskTomorrow = reminder(id = "task", schedule = Schedule.DateOnly(date("2026-10-02")))

    private fun plannedAt(plan: Plan): List<Instant> = plan.toCreate.map { it.plannedAt }

    @Test
    fun createsOnlyFutureRingsInsideTheWindow() {
        val plan = planner.plan(listOf(habit, taskTomorrow), emptyList())

        assertThat(plannedAt(plan)).containsExactly(
            ist("2026-10-01T21:00"),
            ist("2026-10-02T09:00"), // habit
            ist("2026-10-02T09:00"), // task
            ist("2026-10-02T21:00"),
            ist("2026-10-03T09:00"),
        )
        assertThat(plan.toCreate.map { it.state }.toSet()).containsExactly(OccurrenceState.SCHEDULED)
        assertThat(plan.toCancel).isEmpty()
        assertThat(plan.toKeep).isEmpty()
        assertThat(plan.hasChanges).isTrue()
    }

    @Test
    fun createdOccurrencesAreSortedByFireTimeThenId() {
        val plan = planner.plan(listOf(taskTomorrow, habit), emptyList())

        val sorted = plan.toCreate.sortedWith(compareBy({ it.fireAt }, { it.id }))
        assertThat(plan.toCreate).containsExactlyElementsIn(sorted).inOrder()
    }

    @Test
    fun windowStartIsInclusiveAndEndExclusive() {
        val atNow = reminder(id = "now", schedule = Schedule.At(now))
        val atEnd = reminder(id = "end", schedule = Schedule.At(now.plus(Duration.ofHours(48))))
        val justBeforeEnd = reminder(id = "late", schedule = Schedule.At(now.plus(Duration.ofHours(48)).minusMillis(1)))
        val justPast = reminder(id = "past", schedule = Schedule.At(now.minusMillis(1)))

        val plan = planner.plan(listOf(atNow, atEnd, justBeforeEnd, justPast), emptyList())

        assertThat(plan.toCreate.map { it.reminderId }).containsExactly("now", "late")
    }

    @Test
    fun customWindow() {
        val shortPlanner = OccurrencePlanner(fixedClock(now), window = Duration.ofHours(24))

        val plan = shortPlanner.plan(listOf(habit), emptyList())

        assertThat(plannedAt(plan)).containsExactly(ist("2026-10-01T21:00"), ist("2026-10-02T09:00"))
    }

    @Test
    fun planningIsIdempotent() {
        val reminders = listOf(habit, taskTomorrow, nyHabit(), birthday())
        val first = planner.plan(reminders, emptyList())
        val applied = first.applyTo(emptyList())

        val second = planner.plan(reminders, applied)

        assertThat(second.hasChanges).isFalse()
        assertThat(second.toCreate).isEmpty()
        assertThat(second.toCancel).isEmpty()
        assertThat(second.toKeep).containsExactlyElementsIn(applied)
        assertThat(second.applyTo(applied)).containsExactlyElementsIn(applied)
    }

    @Test
    fun rollingForwardHourlyCreatesEachRingExactlyOnce() {
        val reminders = listOf(habit, taskTomorrow, nyHabit(), birthday())
        var stored = emptyList<Occurrence>()
        var t = now
        repeat(24 * 7) {
            val plan = OccurrencePlanner(fixedClock(t)).plan(reminders, stored)
            assertThat(plan.toCancel).isEmpty()
            stored = plan.applyTo(stored)
            t = t.plus(Duration.ofHours(1))
        }

        assertThat(stored.map { it.id }).containsNoDuplicates()
        val expected = reminders.flatMap { r ->
            ScheduleExpander().fireTimes(r, now, t.minus(Duration.ofHours(1)).plus(Duration.ofHours(48)))
                .map { OccurrenceKey.of(r, it).id }
        }
        assertThat(stored.map { it.id }).containsExactlyElementsIn(expected)
    }

    @Test
    fun disabledReminderCancelsPendingAndCreatesNothing() {
        val existing = planner.plan(listOf(taskTomorrow), emptyList()).toCreate

        val plan = planner.plan(listOf(taskTomorrow.copy(enabled = false)), existing)

        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toCancel).containsExactly(Cancellation(existing.single(), CancelReason.REMINDER_DISABLED))
    }

    @Test
    fun removedReminderCancelsScheduledAndSnoozed() {
        val scheduled = Occurrence.scheduled(taskTomorrow, ist("2026-10-02T09:00"))
        val snoozed = Occurrence.scheduled(taskTomorrow, ist("2026-09-30T09:00"))
            .copy(state = OccurrenceState.SNOOZED, fireAt = ist("2026-10-01T10:05"), ringBacks = 1)

        val plan = planner.plan(emptyList(), listOf(scheduled, snoozed))

        assertThat(plan.toCancel.map { it.reason }.toSet()).containsExactly(CancelReason.REMINDER_REMOVED)
        assertThat(plan.toCancel.map { it.occurrence }).containsExactly(scheduled, snoozed)
    }

    @Test
    fun changedScheduleCancelsOldAndCreatesNew() {
        val existing = planner.plan(listOf(taskTomorrow), emptyList()).applyTo(emptyList())
        val moved = taskTomorrow.copy(schedule = Schedule.DateOnly(date("2026-10-02"), time = time("11:00")))

        val plan = planner.plan(listOf(moved), existing)

        assertThat(plan.toCancel).containsExactly(Cancellation(existing.single(), CancelReason.SCHEDULE_CHANGED))
        assertThat(plannedAt(plan)).containsExactly(ist("2026-10-02T11:00"))
    }

    @Test
    fun changedDefaultTimeReschedulesDateOnlyItems() {
        val existing = planner.plan(listOf(taskTomorrow), emptyList()).applyTo(emptyList())
        val newDefaults = DefaultTimes.DEFAULT.with(SourceType.GOOGLE_TASKS, time("07:30"))

        val plan = OccurrencePlanner(fixedClock(now), newDefaults).plan(listOf(taskTomorrow), existing)

        assertThat(plan.toCancel.map { it.reason }).containsExactly(CancelReason.SCHEDULE_CHANGED)
        assertThat(plannedAt(plan)).containsExactly(ist("2026-10-02T07:30"))
    }

    @Test
    fun changedExternalIdIsTreatedAsAScheduleChange() {
        val existing = planner.plan(listOf(taskTomorrow), emptyList()).applyTo(emptyList())

        val plan = planner.plan(listOf(taskTomorrow.copy(externalId = "other")), existing)

        assertThat(plan.toCancel.map { it.reason }).containsExactly(CancelReason.SCHEDULE_CHANGED)
        assertThat(plan.toCreate.single().id).isEqualTo(OccurrenceKey(SourceType.GOOGLE_TASKS, "other", ist("2026-10-02T09:00")).id)
    }

    @Test
    fun terminalOccurrencesAreKeptAndNeverRecreated() {
        val skipped = Occurrence.scheduled(taskTomorrow, ist("2026-10-02T09:00")).copy(state = OccurrenceState.SKIPPED)
        val doneOfRemoved = Occurrence.scheduled(habit, ist("2026-10-01T09:00")).copy(state = OccurrenceState.DONE)

        val plan = planner.plan(listOf(taskTomorrow), listOf(skipped, doneOfRemoved))

        assertThat(plan.toCreate).isEmpty()
        assertThat(plan.toCancel).isEmpty()
        assertThat(plan.toKeep).containsExactly(skipped, doneOfRemoved)
    }

    @Test
    fun ringingOccurrenceIsKeptEvenIfReminderRemoved() {
        val ringing = Occurrence.scheduled(taskTomorrow, ist("2026-10-01T09:59")).copy(state = OccurrenceState.RINGING)

        val plan = planner.plan(emptyList(), listOf(ringing))

        assertThat(plan.toKeep).containsExactly(ringing)
        assertThat(plan.hasChanges).isFalse()
    }

    @Test
    fun overdueAndSnoozedOccurrencesOfUnchangedRemindersAreKept() {
        val overdue = Occurrence.scheduled(habit, ist("2026-10-01T09:00"))
        val snoozed = Occurrence.scheduled(habit, ist("2026-09-30T21:00"))
            .copy(state = OccurrenceState.SNOOZED, fireAt = ist("2026-10-01T10:02"), ringBacks = 2)
        val existing = listOf(overdue, snoozed) + planner.plan(listOf(habit), emptyList()).toCreate

        val plan = planner.plan(listOf(habit), existing)

        assertThat(plan.hasChanges).isFalse()
        assertThat(plan.toKeep).containsAtLeast(overdue, snoozed)
    }

    @Test
    fun remindersSharingSourceAndExternalIdAreDeduped() {
        val twin = taskTomorrow.copy(id = "task-copy")

        val plan = planner.plan(listOf(taskTomorrow, twin), emptyList())

        assertThat(plan.toCreate).hasSize(1)
    }

    @Test
    fun lookbackCreatesRecentPastRingsForRecovery() {
        val bootPlanner = OccurrencePlanner(fixedClock(now), lookback = Duration.ofHours(2))

        val plan = bootPlanner.plan(listOf(habit), emptyList())

        assertThat(plannedAt(plan)).contains(ist("2026-10-01T09:00"))
        assertThat(plannedAt(plan)).doesNotContain(ist("2026-09-30T21:00"))
    }

    @Test
    fun explicitNowOverridesClock() {
        val plan = planner.plan(listOf(taskTomorrow), emptyList(), now = ist("2026-10-02T09:00:01"))

        assertThat(plan.toCreate).isEmpty()
    }

    @Test
    fun rejectsNonPositiveWindowAndNegativeLookback() {
        assertThrows(IllegalArgumentException::class.java) {
            OccurrencePlanner(fixedClock(now), window = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OccurrencePlanner(fixedClock(now), lookback = Duration.ofMinutes(-1))
        }
    }

    private fun nyHabit(): Reminder = reminder(
        id = "ny",
        sourceType = SourceType.HABIT,
        zone = NEW_YORK,
        schedule = Schedule.Recurring(
            RecurrenceRule(setOf(DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), setOf(time("02:30"), time("07:00")), date("2026-01-01")),
        ),
    )

    private fun birthday(): Reminder = reminder(
        id = "bday",
        sourceType = SourceType.BIRTHDAY,
        schedule = Schedule.Annual(MonthDay.of(10, 3), sinceYear = 1990, lead = LeadOffset.days(1)),
    )
}
