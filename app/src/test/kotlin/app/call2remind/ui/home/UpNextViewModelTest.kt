package app.call2remind.ui.home

import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.sync.SyncCoordinator
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeNotificationAccess
import app.call2remind.testing.FakeReminderSource
import app.call2remind.testing.T0
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.hours
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpNextViewModelTest {
    private val h = EngineHarness()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calendar = FakeReminderSource(SourceType.CALENDAR)
    private val sync = SyncCoordinator(setOf(calendar), h.sources, h.reminders, h.engine, h.settings, FakeNotificationAccess(), h.clock)
    private lateinit var vm: UpNextViewModel
    private lateinit var collector: Job

    private val standup = reminder("standup", Schedule.At(T0.plus(hours(1))), title = "Standup")
    private val gym = reminder("gym", Schedule.At(T0.plus(hours(26))), title = "Gym")

    @Before
    fun setUp() {
        runBlocking { h.engine.upsertReminders(listOf(standup, gym)) }
        vm = UpNextViewModel(h.occurrences, h.engine, sync, h.clock, appScope)
        collector = CoroutineScope(Dispatchers.Main).launch { vm.state.collect { } }
        awaitUntil(message = "timeline") { vm.state.value.model.next?.title == "Standup" }
    }

    @After
    fun tearDown() {
        collector.cancel()
        appScope.cancel()
        h.close()
    }

    private fun rowOf(title: String): TimelineRow =
        vm.state.value.model.sections.flatMap { it.rows }.first { it.title == title }

    private fun stateOf(row: TimelineRow): OccurrenceState? = runBlocking { h.occurrences.get(row.occurrenceId)?.state }

    @Test
    fun groupsTodayAndTomorrow() {
        val sections = vm.state.value.model.sections
        assertThat(sections.map { it.group }).containsExactly(TimelineGroup.TODAY, TimelineGroup.TOMORROW).inOrder()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun swipeDoneHidesAtOnceAndCommitsWhenTheUndoWindowEnds() {
        val row = rowOf("Standup")

        vm.onSwipe(row, SwipeKind.DONE)

        awaitUntil(message = "hidden") { vm.state.value.model.next?.title == "Gym" }
        assertThat(vm.state.value.pending).isEqualTo(PendingSwipe(row.occurrenceId, "Standup", SwipeKind.DONE))
        // Nothing is written during the undo window.
        assertThat(stateOf(row)).isEqualTo(OccurrenceState.SCHEDULED)

        vm.commitPending()

        awaitUntil(message = "done") { stateOf(row) == OccurrenceState.DONE }
        awaitUntil(message = "pending cleared") { vm.state.value.pending == null }
        assertThat(h.alarms.armed).doesNotContainKey(row.occurrenceId)
    }

    @Test
    fun swipeSkipCommitsSkipped() {
        val row = rowOf("Gym")

        vm.onSwipe(row, SwipeKind.SKIP)
        vm.commitPending()

        awaitUntil(message = "skipped") { stateOf(row) == OccurrenceState.SKIPPED }
    }

    @Test
    fun undoBringsTheRowBackAndWritesNothing() {
        val row = rowOf("Standup")
        vm.onSwipe(row, SwipeKind.DONE)
        awaitUntil(message = "hidden") { vm.state.value.model.next?.title == "Gym" }

        vm.undo()

        awaitUntil(message = "back") { vm.state.value.model.next?.title == "Standup" }
        assertThat(vm.state.value.pending).isNull()
        vm.commitPending()
        assertThat(stateOf(row)).isEqualTo(OccurrenceState.SCHEDULED)
    }

    @Test
    fun aSecondSwipeCommitsTheFirst() {
        val standupRow = rowOf("Standup")
        val gymRow = rowOf("Gym")

        vm.onSwipe(standupRow, SwipeKind.DONE)
        vm.onSwipe(gymRow, SwipeKind.SKIP)

        awaitUntil(message = "first committed") { stateOf(standupRow) == OccurrenceState.DONE }
        assertThat(vm.state.value.pending?.occurrenceId).isEqualTo(gymRow.occurrenceId)
        assertThat(stateOf(gymRow)).isEqualTo(OccurrenceState.SCHEDULED)
    }

    @Test
    fun syncNowRunsEverySourceAndReportsSyncing() {
        vm.syncNow()

        awaitUntil(message = "synced") { calendar.requests.isNotEmpty() }
        awaitUntil(message = "idle") { !vm.state.value.syncing }
    }
}
