package app.call2remind.core.speech

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.instant
import app.call2remind.core.Fixtures.reminder
import app.call2remind.core.Fixtures.time
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId
import java.util.Locale

class SpeechTextTest {

    private val us = Locale.US

    private fun speak(r: Reminder, planned: Instant, zone: ZoneId = r.zone): String =
        SpeechText.build(r, Occurrence.scheduled(r, planned), zone, us)

    @Test
    fun timedReminderSaysTheEventTime() {
        val start = at("2026-10-01T10:00", 5, 30)
        val r = reminder(title = "Standup", schedule = Schedule.At(start, LeadOffset.minutes(10)), sourceType = SourceType.CALENDAR)

        assertThat(speak(r, start.minusSeconds(600))).isEqualTo("Reminder: Standup. 10:00 AM.")
    }

    @Test
    fun timeIsFormattedInTheRequestedZone() {
        val start = instant("2026-10-01T15:45:00Z")
        val r = reminder(title = "Flight", schedule = Schedule.At(start))

        assertThat(speak(r, start, NEW_YORK)).isEqualTo("Reminder: Flight. 11:45 AM.")
        assertThat(speak(r, start, KOLKATA)).isEqualTo("Reminder: Flight. 9:15 PM.")
    }

    @Test
    fun timeIsSpokenInTheDeviceZoneNotTheReminderZone() {
        val start = instant("2026-10-01T04:30:00Z")
        val inKolkata = reminder(title = "Standup", zone = KOLKATA, schedule = Schedule.At(start))
        val inNewYork = inKolkata.copy(zone = NEW_YORK)
        val london = ZoneId.of("Europe/London")

        assertThat(speak(inKolkata, start, london)).isEqualTo("Reminder: Standup. 5:30 AM.")
        assertThat(speak(inNewYork, start, london)).isEqualTo("Reminder: Standup. 5:30 AM.")
    }

    @Test
    fun dayLeadAcrossDstGapSpeaksTheEventTime() {
        val rule = RecurrenceRule(DayOfWeek.entries.toSet(), setOf(time("02:30")), date("2026-01-01"))
        val r = reminder(
            title = "Night shift",
            zone = NEW_YORK,
            sourceType = SourceType.HABIT,
            schedule = Schedule.Recurring(rule, LeadOffset.days(1)),
        )
        // The ring for the Mar 9 02:30 event: one day earlier lands in the Mar 8 DST gap → 03:30 EDT.
        val planned = at("2026-03-08T03:30", -4)

        assertThat(speak(r, planned, NEW_YORK)).isEqualTo("Reminder: Night shift. 2:30 AM.")
    }

    @Test
    fun recurringHabitUsesPlannedTimeEvenWhenSnoozed() {
        val rule = RecurrenceRule(DayOfWeek.entries.toSet(), setOf(time("07:30")), date("2026-01-01"))
        val r = reminder(title = "Meds", schedule = Schedule.Recurring(rule), sourceType = SourceType.HABIT)
        val planned = at("2026-10-01T07:30", 5, 30)
        val snoozed = Occurrence.scheduled(r, planned)
            .copy(state = OccurrenceState.SNOOZED, fireAt = planned.plusSeconds(600))

        assertThat(SpeechText.build(r, snoozed, KOLKATA, us)).isEqualTo("Reminder: Meds. 7:30 AM.")
    }

    @Test
    fun notesWinOverTimeAndAreCleanedUp() {
        val r = reminder(
            title = "Pay rent",
            notes = "  Transfer to\n  landlord   today ",
            schedule = Schedule.At(instant("2026-10-01T04:30:00Z")),
        )

        assertThat(speak(r, instant("2026-10-01T04:30:00Z"))).isEqualTo("Reminder: Pay rent. Transfer to landlord today.")
    }

    @Test
    fun existingPunctuationIsNotDoubled() {
        val r = reminder(title = "Call mom!", notes = "Ask about Sunday?", schedule = Schedule.DateOnly(date("2026-10-01")))

        assertThat(speak(r, instant("2026-10-01T03:30:00Z"))).isEqualTo("Reminder: Call mom! Ask about Sunday?")
    }

    @Test
    fun longNotesAreTruncatedAtAWordBoundary() {
        val notes = (1..100).joinToString(" ") { "word$it" }
        val r = reminder(title = "Read", notes = notes, schedule = Schedule.DateOnly(date("2026-10-01")))

        val text = speak(r, instant("2026-10-01T03:30:00Z"))

        assertThat(text).startsWith("Reminder: Read. word1 word2")
        assertThat(text).endsWith("…")
        assertThat(text.length).isAtMost("Reminder: Read. ".length + SpeechText.MAX_NOTES_CHARS + 1)
        assertThat(text.removeSuffix("…")).doesNotContain("  ")
        assertThat(text.removeSuffix("…").substringAfterLast(' ')).startsWith("word")
    }

    @Test
    fun dateOnlyWithoutNotesSaysJustTheTitle() {
        val r = reminder(title = "Buy milk", schedule = Schedule.DateOnly(date("2026-10-01")))

        assertThat(speak(r, instant("2026-10-01T03:30:00Z"))).isEqualTo("Reminder: Buy milk.")
    }

    @Test
    fun birthdayDayBeforeSaysTomorrow() {
        val r = reminder(
            title = "Asha's birthday",
            sourceType = SourceType.BIRTHDAY,
            schedule = Schedule.Annual(MonthDay.of(10, 2), lead = LeadOffset.days(1)),
        )
        val r3 = r.copy(schedule = Schedule.Annual(MonthDay.of(10, 4), lead = LeadOffset.days(3)))

        assertThat(speak(r, instant("2026-10-01T03:30:00Z"))).isEqualTo("Reminder: Asha's birthday. Tomorrow.")
        assertThat(speak(r3, instant("2026-10-01T03:30:00Z"))).isEqualTo("Reminder: Asha's birthday. In 3 days.")
    }

    @Test
    fun blankTitleFallsBack() {
        val r = reminder(title = "   ", schedule = Schedule.DateOnly(date("2026-10-01")))

        assertThat(speak(r, instant("2026-10-01T03:30:00Z"))).isEqualTo("Reminder: Untitled.")
    }
}
