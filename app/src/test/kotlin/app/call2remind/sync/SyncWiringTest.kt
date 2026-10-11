package app.call2remind.sync

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.SourceRepository
import app.call2remind.sources.SourceIds
import app.call2remind.sources.habit.HabitRepository
import app.call2remind.sources.samsung.SamsungReminderHandler
import app.call2remind.testing.awaitUntil
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** The real Hilt graph: every source is registered and degrades gracefully without access. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SyncWiringTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var coordinator: SyncCoordinator

    @Inject lateinit var sources: SourceRepository

    @Inject lateinit var habits: HabitRepository

    @Inject lateinit var observer: CalendarChangeObserver

    @Inject lateinit var samsung: SamsungReminderHandler

    @Inject lateinit var db: Call2RemindDb

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() = db.close()

    @Test
    fun everySourceIsRegistered() {
        assertThat(coordinator.types).containsExactly(
            SourceType.CALENDAR,
            SourceType.SAMSUNG_REMINDER,
            SourceType.MS_TODO,
            SourceType.GOOGLE_TASKS,
            SourceType.BIRTHDAY,
        ).inOrder()
    }

    @Test
    fun withoutPermissionsOrAccountsNothingCrashes() = runBlocking<Unit> {
        val result = coordinator.sync(SyncScope.ALL, force = true)

        assertThat(result.needsRetry).isFalse()
        assertThat(coordinator.currentState(SourceType.CALENDAR)).isEqualTo(SyncState.NeedsPermission(Manifest.permission.READ_CALENDAR))
        assertThat(coordinator.currentState(SourceType.BIRTHDAY)).isEqualTo(SyncState.NeedsPermission(Manifest.permission.READ_CONTACTS))
        assertThat(coordinator.currentState(SourceType.GOOGLE_TASKS)).isEqualTo(SyncState.NotConnected)
        assertThat(coordinator.currentState(SourceType.MS_TODO)).isEqualTo(SyncState.NotConnected)
        assertThat(coordinator.currentState(SourceType.SAMSUNG_REMINDER)).isEqualTo(SyncState.Disabled)
    }

    @Test
    fun eventReminderBroadcastSyncsTheCalendar() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        EventReminderReceiver().onReceive(app, Intent(CalendarContract.ACTION_EVENT_REMINDER))

        awaitUntil(message = "calendar synced") { runBlocking { sources.get(SourceIds.CALENDAR)?.lastSyncAt != null } }
    }

    @Test
    fun otherBroadcastsAreIgnored() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        EventReminderReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        Thread.sleep(200)
        assertThat(runBlocking { sources.get(SourceIds.CALENDAR) }).isNull()
    }

    @Test
    fun calendarObserverNeedsThePermissionAndTriggersADebouncedSync() {
        assertThat(observer.ensureRegistered()).isFalse()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertThat(observer.ensureRegistered()).isTrue()
        assertThat(observer.ensureRegistered()).isTrue()

        app.contentResolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)

        awaitUntil(timeoutMs = 8_000, message = "debounced calendar sync") {
            runBlocking { sources.get(SourceIds.CALENDAR)?.lastSyncAt != null }
        }
    }

    @Test
    fun habitsAreAvailable() = runBlocking<Unit> {
        val rule = RecurrenceRule(setOf(DayOfWeek.MONDAY), setOf(LocalTime.of(7, 0)), LocalDate.of(2026, 3, 1))

        val habit = habits.create("Gym", rule)

        assertThat(habits.get(habit.id)?.title).isEqualTo("Gym")
        assertThat(sources.get(SourceIds.HABITS)).isNotNull()
    }
}
