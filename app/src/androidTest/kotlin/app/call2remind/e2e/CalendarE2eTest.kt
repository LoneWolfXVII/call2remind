package app.call2remind.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.LocalCalendar
import app.call2remind.e2e.support.Waits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Auto-sync from the system calendar provider: an event with a reminder in a local calendar is
 * picked up (content observer → calendar sync) and planned at start − reminder minutes; deleting
 * it cancels the planned ring.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class CalendarE2eTest {
    @get:Rule
    val e2e = E2eRule()

    @Test
    fun eventReminderIsPlannedAtItsOffsetAndRemovedWithTheEvent() {
        e2e.onTearDown("delete test calendar") { LocalCalendar.deleteAll() }
        val observer = AppDriver.app.calendarObserver
        assertWithMessage("calendar observer registered (READ_CALENDAR granted)").that(observer.ensureRegistered()).isTrue()

        val calendarId = LocalCalendar.create()
        val title = AppDriver.newTitle("meeting")
        val start = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES)
        LocalCalendar.insertTimedEvent(calendarId, title, start, durationMinutes = 30, reminderMinutes = 10)
        // The provider notifies our observer; nudge it too in case the change raced its registration.
        observer.onCalendarChanged()

        val reminder = Waits.value("calendar sync stored '$title'", 30_000) { AppDriver.reminderByTitle(title) }
        assertThat(reminder.sourceType).isEqualTo(SourceType.CALENDAR)
        val expected = start.minus(Duration.ofMinutes(10))
        val occurrence = Waits.value("occurrence of '$title' planned", 15_000) { AppDriver.activeFor(reminder.id).singleOrNull() }
        assertThat(occurrence.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(occurrence.plannedAt).isEqualTo(expected)
        assertThat(occurrence.fireAt).isEqualTo(expected)
        Waits.until("alarm armed for the event reminder at $expected", 10_000) { AppDriver.nextAlarmClockAt() == expected }
        assertThat(Device.pendingAppAlarmCount()).isAtLeast(1)

        LocalCalendar.deleteAll()
        observer.onCalendarChanged()
        Waits.until("occurrence of the deleted event cancelled", 30_000) { AppDriver.activeFor(reminder.id).isEmpty() }
        Waits.until("its alarm cancelled", 10_000) { AppDriver.nextAlarmClockAt() != expected }
    }
}
