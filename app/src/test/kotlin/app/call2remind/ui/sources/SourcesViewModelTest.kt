package app.call2remind.ui.sources

import app.call2remind.core.model.SourceType
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.calendar.CalendarInfo
import app.call2remind.sources.calendar.FakeCalendarReader
import app.call2remind.sources.habit.ReminderBackedHabitRepository
import app.call2remind.sync.SyncCoordinator
import app.call2remind.sync.SyncState
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeNotificationAccess
import app.call2remind.testing.FakeReminderSource
import app.call2remind.testing.awaitUntil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourcesViewModelTest {
    private val h = EngineHarness()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calendar = FakeReminderSource(SourceType.CALENDAR)
    private val birthdays = FakeReminderSource(SourceType.BIRTHDAY)
    private val access = FakeNotificationAccess()
    private val sync = SyncCoordinator(setOf(calendar, birthdays), h.sources, h.reminders, h.engine, h.settings, access, h.clock)
    private val habits = ReminderBackedHabitRepository(h.reminders, h.sources, h.engine, h.clock)
    private val reader = FakeCalendarReader(
        calendars = listOf(CalendarInfo(1, "Work", "me@work", "com.google"), CalendarInfo(2, "Personal", "me@home", "com.google")),
    )
    private lateinit var vm: SourcesViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        vm = SourcesViewModel(sync, h.settings, habits, reader, access, Dispatchers.IO, appScope, h.clock)
        collector = CoroutineScope(Dispatchers.Main).launch { vm.state.collect { } }
        awaitUntil(message = "loaded") { !vm.state.value.loading }
    }

    @After
    fun tearDown() {
        collector.cancel()
        appScope.cancel()
        h.close()
    }

    @Test
    fun turningASourceOffGoesThroughTheCoordinator() {
        vm.setEnabled(SourceType.BIRTHDAY, false)

        awaitUntil(message = "disabled") { vm.state.value.status(SourceType.BIRTHDAY)?.state == SyncState.Disabled }
        assertThat(h.settings.state.value.sources.isEnabled(SourceType.BIRTHDAY)).isFalse()
        assertThat(vm.state.value.settings.isEnabled(SourceType.BIRTHDAY)).isFalse()

        vm.setEnabled(SourceType.BIRTHDAY, true)
        awaitUntil(message = "synced") { birthdays.requests.isNotEmpty() }
        assertThat(h.settings.state.value.sources.isEnabled(SourceType.BIRTHDAY)).isTrue()
    }

    @Test
    fun aMissingPermissionNeedsAttention() {
        calendar.availability = SourceAvailability.NeedsPermission("android.permission.READ_CALENDAR")

        vm.refresh()

        awaitUntil(message = "needs permission") { vm.state.value.status(SourceType.CALENDAR)?.state is SyncState.NeedsPermission }
        assertThat(vm.state.value.summary).isEqualTo(SourcesSummary.NeedsAttention(1))
    }

    @Test
    fun syncAllMarksEverythingSynced() {
        vm.syncAll()

        awaitUntil(message = "synced") { vm.state.value.summary is SourcesSummary.Synced && (vm.state.value.summary as SourcesSummary.Synced).at != null }
        assertThat(calendar.requests).isNotEmpty()
    }

    @Test
    fun calendarPickerExcludesAndIncludes() {
        vm.loadCalendars()
        awaitUntil(message = "calendars") { vm.state.value.calendars?.size == 2 }
        assertThat(vm.state.value.calendars!!.all { it.included }).isTrue()

        vm.setCalendarIncluded(2, false)

        awaitUntil(message = "excluded") { h.settings.state.value.sources.excludedCalendarIds == setOf(2L) }
        awaitUntil(message = "ui") { vm.state.value.calendars?.single { it.info.id == 2L }?.included == false }

        val before = calendar.requests.size
        vm.onCalendarsClosed()
        awaitUntil(message = "resynced") { calendar.requests.size > before }

        vm.setCalendarIncluded(2, true)
        awaitUntil(message = "included") { h.settings.state.value.sources.excludedCalendarIds.isEmpty() }
    }

    @Test
    fun samsungNeedsNotificationAccessUntilGranted() {
        vm.setEnabled(SourceType.SAMSUNG_REMINDER, true)
        awaitUntil(message = "needs access") { vm.state.value.status(SourceType.SAMSUNG_REMINDER)?.state is SyncState.NeedsPermission }

        access.granted = true
        vm.refresh()

        awaitUntil(message = "listening") { vm.state.value.status(SourceType.SAMSUNG_REMINDER)?.state == SyncState.Idle }
        assertThat(vm.state.value.notificationAccess).isTrue()
    }

    @Test
    fun dayBeforeBirthdaysIsStoredAndResyncs() {
        vm.setBirthdayDayBefore(true)

        awaitUntil(message = "stored") { h.settings.state.value.sources.birthdayDayBefore }
        awaitUntil(message = "resynced") { birthdays.requests.any { it.settings.birthdayDayBefore } }
    }
}
