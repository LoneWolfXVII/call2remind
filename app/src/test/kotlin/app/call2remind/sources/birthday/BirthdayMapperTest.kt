package app.call2remind.sources.birthday

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.MonthDay
import java.time.ZoneId

class BirthdayMapperTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun row(name: String?, date: String?, id: Long = 1) = ContactBirthdayRow(id, name, date)

    private fun map(vararg rows: ContactBirthdayRow, dayBefore: Boolean = false) = BirthdayMapper.map(rows.toList(), dayBefore, zone)

    @Test
    fun fullDateBecomesAnnualWithBirthYear() {
        val reminder = map(row("Asha Rao", "1990-05-17")).single()

        assertThat(reminder.sourceType).isEqualTo(SourceType.BIRTHDAY)
        assertThat(reminder.externalId).isEqualTo("asha rao|05-17")
        assertThat(reminder.id).isEqualTo(ReminderIds.of(SourceType.BIRTHDAY, "asha rao|05-17"))
        assertThat(reminder.title).isEqualTo("Asha Rao's birthday")
        assertThat(reminder.schedule).isEqualTo(Schedule.Annual(MonthDay.of(5, 17), sinceYear = 1990))
        assertThat(reminder.zone).isEqualTo(zone)
    }

    @Test
    fun supportedFormats() {
        val result = map(
            row("A", "1990-05-17", 1),
            row("B", "--06-01", 2),
            row("C", "19851224", 3),
            row("D", "--0229", 4),
            row("E", "1992-02-29T00:00:00.000Z", 5),
            row("F", "0000-07-04", 6),
        ).associate { it.title to it.schedule }

        assertThat(result).containsExactly(
            "A's birthday", Schedule.Annual(MonthDay.of(5, 17), 1990),
            "B's birthday", Schedule.Annual(MonthDay.of(6, 1), null),
            "C's birthday", Schedule.Annual(MonthDay.of(12, 24), 1985),
            "D's birthday", Schedule.Annual(MonthDay.of(2, 29), null),
            "E's birthday", Schedule.Annual(MonthDay.of(2, 29), 1992),
            "F's birthday", Schedule.Annual(MonthDay.of(7, 4), null),
        )
    }

    @Test
    fun invalidDatesAndNamelessRowsAreDropped() {
        val result = map(
            row("Bad", "1990-13-01"),
            row("Worse", "not a date"),
            row("Empty", null),
            row(null, "1990-05-17"),
            row("   ", "1990-05-17"),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun sameNameAndDayFromSeveralAccountsIsDeduped() {
        val result = map(
            row("Asha Rao", "--05-17", 1),
            row("asha  rao", "1990-05-17", 2),
            row(" ASHA RAO ", "1990-05-17", 3),
        )

        val reminder = result.single()
        assertThat(reminder.title).isEqualTo("asha rao's birthday")
        assertThat((reminder.schedule as Schedule.Annual).sinceYear).isEqualTo(1990)
    }

    @Test
    fun knownYearWinsButFirstNameIsKeptOtherwise() {
        val result = map(row("Ravi", "1988-01-02", 1), row("RAVI", "--01-02", 2))

        assertThat(result.single().title).isEqualTo("Ravi's birthday")
        assertThat((result.single().schedule as Schedule.Annual).sinceYear).isEqualTo(1988)
    }

    @Test
    fun accentsAreIgnoredForDedupe() {
        val result = map(row("José Núñez", "--03-03", 1), row("Jose Nunez", "--03-03", 2))

        assertThat(result.map { it.externalId }).containsExactly("jose nunez|03-03")
    }

    @Test
    fun sameNameOnDifferentDaysAreDifferentPeople() {
        assertThat(map(row("Sam", "--03-03", 1), row("Sam", "--04-04", 2))).hasSize(2)
    }

    @Test
    fun dayBeforeAddsASecondReminderWithADayLead() {
        val result = map(row("Asha", "1990-05-17"), dayBefore = true)

        assertThat(result).hasSize(2)
        val (onDay, before) = result
        assertThat(onDay.schedule.lead).isEqualTo(LeadOffset.NONE)
        assertThat(before.externalId).isEqualTo("asha|05-17" + BirthdayMapper.DAY_BEFORE_SUFFIX)
        assertThat(before.title).isEqualTo("Asha's birthday is tomorrow")
        assertThat(before.schedule).isEqualTo(Schedule.Annual(MonthDay.of(5, 17), 1990, lead = LeadOffset.days(1)))
        assertThat(before.id).isNotEqualTo(onDay.id)
    }

    @Test
    fun namesAreNormalizedConsistently() {
        assertThat(BirthdayMapper.normalizeName("  Émile   ZOLA ")).isEqualTo("emile zola")
    }
}
