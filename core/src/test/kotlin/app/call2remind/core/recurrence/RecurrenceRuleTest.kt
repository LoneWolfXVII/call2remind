package app.call2remind.core.recurrence

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.LONDON
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.time
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration

class RecurrenceRuleTest {

    private val everyDay: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    private fun ist(localDateTime: String) = at(localDateTime, 5, 30)

    @Test
    fun weeklyOnSelectedDaysAtSeveralTimes() {
        val rule = RecurrenceRule(
            daysOfWeek = setOf(MONDAY, WEDNESDAY),
            times = setOf(time("20:00"), time("08:00")),
            startDate = date("2026-01-05"),
        )

        val result = rule.occurrencesBetween(ist("2026-01-05T00:00"), ist("2026-01-12T00:00"), KOLKATA)

        assertThat(result).containsExactly(
            ist("2026-01-05T08:00"),
            ist("2026-01-05T20:00"),
            ist("2026-01-07T08:00"),
            ist("2026-01-07T20:00"),
        ).inOrder()
    }

    @Test
    fun windowIsStartInclusiveEndExclusive() {
        val rule = RecurrenceRule(setOf(MONDAY, WEDNESDAY), setOf(time("08:00")), date("2026-01-05"))

        val result = rule.occurrencesBetween(ist("2026-01-05T08:00"), ist("2026-01-07T08:00"), KOLKATA)

        assertThat(result).containsExactly(ist("2026-01-05T08:00"))
    }

    @Test
    fun emptyOrInvertedWindowYieldsNothing() {
        val rule = RecurrenceRule(everyDay, setOf(time("08:00")), date("2026-01-05"))
        val t = ist("2026-01-06T08:00")

        assertThat(rule.occurrencesBetween(t, t, KOLKATA)).isEmpty()
        assertThat(rule.occurrencesBetween(t, t.minusSeconds(60), KOLKATA)).isEmpty()
    }

    @Test
    fun nothingBeforeStartDate() {
        val rule = RecurrenceRule(setOf(MONDAY, WEDNESDAY), setOf(time("08:00")), date("2026-01-07"))

        val result = rule.occurrencesBetween(ist("2026-01-01T00:00"), ist("2026-01-08T00:00"), KOLKATA)

        assertThat(result).containsExactly(ist("2026-01-07T08:00"))
    }

    @Test
    fun intervalWeeksCountsFromTheIsoWeekOfStartDate() {
        // Start Wed Jan 7 → week 0 is Mon Jan 5..Sun Jan 11. Mondays in weeks 0, 2, 4 are active,
        // but Jan 5 is before the start date.
        val rule = RecurrenceRule(setOf(MONDAY), setOf(time("07:00")), date("2026-01-07"), intervalWeeks = 2)

        val result = rule.occurrencesBetween(ist("2026-01-01T00:00"), ist("2026-02-05T00:00"), KOLKATA)

        assertThat(result).containsExactly(ist("2026-01-19T07:00"), ist("2026-02-02T07:00")).inOrder()
    }

    @Test
    fun untilDateIsInclusive() {
        val rule = RecurrenceRule(
            everyDay,
            setOf(time("09:00")),
            date("2026-01-05"),
            end = RecurrenceEnd.Until(date("2026-01-07")),
        )

        val result = rule.occurrencesBetween(ist("2026-01-01T00:00"), ist("2026-02-01T00:00"), KOLKATA)

        assertThat(result).containsExactly(
            ist("2026-01-05T09:00"),
            ist("2026-01-06T09:00"),
            ist("2026-01-07T09:00"),
        ).inOrder()
        assertThat(rule.matchesDate(date("2026-01-07"))).isTrue()
        assertThat(rule.matchesDate(date("2026-01-08"))).isFalse()
    }

    @Test
    fun countLimitsTotalOccurrencesFromStartRegardlessOfWindow() {
        val rule = RecurrenceRule(
            everyDay,
            setOf(time("08:00"), time("20:00")),
            date("2026-01-05"),
            end = RecurrenceEnd.Count(3),
        )

        assertThat(rule.occurrencesBetween(ist("2026-01-01T00:00"), ist("2026-02-01T00:00"), KOLKATA))
            .containsExactly(ist("2026-01-05T08:00"), ist("2026-01-05T20:00"), ist("2026-01-06T08:00"))
            .inOrder()
        assertThat(rule.occurrencesBetween(ist("2026-01-06T00:00"), ist("2026-02-01T00:00"), KOLKATA))
            .containsExactly(ist("2026-01-06T08:00"))
        assertThat(rule.occurrencesBetween(ist("2026-01-07T00:00"), ist("2026-02-01T00:00"), KOLKATA)).isEmpty()
    }

    @Test
    fun monthEndAndLeapDay() {
        val rule = RecurrenceRule(everyDay, setOf(time("23:30")), date("2028-02-27"))

        val result = rule.occurrencesBetween(ist("2028-02-27T00:00"), ist("2028-03-02T00:00"), KOLKATA)

        assertThat(result).containsExactly(
            ist("2028-02-27T23:30"),
            ist("2028-02-28T23:30"),
            ist("2028-02-29T23:30"),
            ist("2028-03-01T23:30"),
        ).inOrder()
    }

    @Test
    fun monthEndInNonLeapYear() {
        val rule = RecurrenceRule(everyDay, setOf(time("00:15")), date("2026-01-01"))

        val result = rule.occurrencesBetween(ist("2026-01-30T00:00"), ist("2026-03-02T00:00"), KOLKATA)

        assertThat(result).hasSize(31) // Jan 30, 31 + 28 days of Feb + Mar 1
        assertThat(result.first()).isEqualTo(ist("2026-01-30T00:15"))
        assertThat(result.last()).isEqualTo(ist("2026-03-01T00:15"))
        assertThat(result).doesNotContain(ist("2026-03-02T00:15"))
    }

    @Test
    fun newYorkSpringForwardGapShiftsForwardByTheGap() {
        // 2026-03-08 02:00 EST → 03:00 EDT: 02:30 does not exist and becomes 03:30 EDT.
        val rule = RecurrenceRule(everyDay, setOf(time("02:30")), date("2026-03-01"))

        val result = rule.occurrencesBetween(at("2026-03-07T00:00", -5), at("2026-03-10T00:00", -4), NEW_YORK)

        assertThat(result).containsExactly(
            at("2026-03-07T02:30", -5),
            at("2026-03-08T03:30", -4),
            at("2026-03-09T02:30", -4),
        ).inOrder()
    }

    @Test
    fun timesCollapsingIntoTheSameInstantInAGapAreProducedOnce() {
        val rule = RecurrenceRule(setOf(SUNDAY), setOf(time("02:30"), time("03:30")), date("2026-03-08"))

        val result = rule.occurrencesBetween(at("2026-03-08T00:00", -5), at("2026-03-09T00:00", -4), NEW_YORK)

        assertThat(result).containsExactly(at("2026-03-08T03:30", -4))
    }

    @Test
    fun gapShiftedTimeIsSortedAmongOtherTimes() {
        // 02:15 → 03:15 EDT, which is after 03:00 EDT.
        val rule = RecurrenceRule(setOf(SUNDAY), setOf(time("02:15"), time("03:00")), date("2026-03-08"))

        val result = rule.occurrencesBetween(at("2026-03-08T00:00", -5), at("2026-03-09T00:00", -4), NEW_YORK)

        assertThat(result).containsExactly(at("2026-03-08T03:00", -4), at("2026-03-08T03:15", -4)).inOrder()
    }

    @Test
    fun countCountsDistinctInstantsAfterGapCollapse() {
        val rule = RecurrenceRule(
            setOf(SUNDAY),
            setOf(time("02:30"), time("03:30")),
            date("2026-03-08"),
            end = RecurrenceEnd.Count(2),
        )

        val result = rule.occurrencesBetween(at("2026-03-01T00:00", -5), at("2026-04-01T00:00", -4), NEW_YORK)

        assertThat(result).containsExactly(at("2026-03-08T03:30", -4), at("2026-03-15T02:30", -4)).inOrder()
    }

    @Test
    fun newYorkFallBackOverlapUsesEarlierOffsetOnce() {
        // 2026-11-01 02:00 EDT → 01:00 EST: 01:30 happens twice; ring at the first (EDT).
        val rule = RecurrenceRule(everyDay, setOf(time("01:30")), date("2026-10-30"))

        val result = rule.occurrencesBetween(at("2026-10-31T00:00", -4), at("2026-11-03T00:00", -5), NEW_YORK)

        assertThat(result).containsExactly(
            at("2026-10-31T01:30", -4),
            at("2026-11-01T01:30", -4),
            at("2026-11-02T01:30", -5),
        ).inOrder()
    }

    @Test
    fun londonSpringForwardAndFallBack() {
        val rule = RecurrenceRule(setOf(SUNDAY), setOf(time("01:30")), date("2026-01-01"))

        val spring = rule.occurrencesBetween(at("2026-03-29T00:00", 0), at("2026-03-30T00:00", 1), LONDON)
        val autumn = rule.occurrencesBetween(at("2026-10-25T00:00", 1), at("2026-10-26T00:00", 0), LONDON)

        // 01:00 GMT → 02:00 BST: 01:30 becomes 02:30 BST.
        assertThat(spring).containsExactly(at("2026-03-29T02:30", 1))
        // 02:00 BST → 01:00 GMT: 01:30 occurs twice; earlier offset (BST) wins, once.
        assertThat(autumn).containsExactly(at("2026-10-25T01:30", 1))
    }

    @Test
    fun kolkataHasNoDstSoDailyRingsAreExactly24HoursApartAllYear() {
        val rule = RecurrenceRule(everyDay, setOf(time("09:00")), date("2026-01-01"))

        val result = rule.occurrencesBetween(ist("2026-01-01T00:00"), ist("2027-01-01T00:00"), KOLKATA)

        assertThat(result).hasSize(365)
        assertThat(result.zipWithNext { a, b -> Duration.between(a, b) }.toSet())
            .containsExactly(Duration.ofHours(24))
    }

    @Test
    fun resultsAreStrictlyIncreasingAcrossDstTransitions() {
        val rule = RecurrenceRule(everyDay, setOf(time("00:30"), time("01:30"), time("02:30")), date("2026-01-01"))

        val result = rule.occurrencesBetween(at("2026-01-01T00:00", -5), at("2027-01-01T00:00", -5), NEW_YORK)

        assertThat(result).containsNoDuplicates()
        assertThat(result).isInStrictOrder()
        // Spring-forward 02:30 becomes 03:30 (still distinct); fall-back 01:30 rings once.
        assertThat(result).hasSize(365 * 3)
    }

    @Test
    fun validation() {
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(emptySet(), setOf(time("08:00")), date("2026-01-01"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(setOf(MONDAY), emptySet(), date("2026-01-01"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(setOf(MONDAY), setOf(time("08:00")), date("2026-01-01"), intervalWeeks = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecurrenceRule(
                setOf(MONDAY),
                setOf(time("08:00")),
                date("2026-01-10"),
                end = RecurrenceEnd.Until(date("2026-01-09")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { RecurrenceEnd.Count(0) }
    }
}
