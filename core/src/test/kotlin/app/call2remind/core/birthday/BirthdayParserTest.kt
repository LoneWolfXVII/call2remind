package app.call2remind.core.birthday

import app.call2remind.core.Fixtures.date
import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Schedule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.MonthDay

class BirthdayParserTest {

    @Test
    fun parsesAllSupportedFormats() {
        val withYear = Birthday(MonthDay.of(5, 17), 1990)
        val noYear = Birthday(MonthDay.of(5, 17), null)

        assertThat(BirthdayParser.parse("1990-05-17")).isEqualTo(withYear)
        assertThat(BirthdayParser.parse("19900517")).isEqualTo(withYear)
        assertThat(BirthdayParser.parse("--05-17")).isEqualTo(noYear)
        assertThat(BirthdayParser.parse("--0517")).isEqualTo(noYear)
        assertThat(BirthdayParser.parse("  1990-05-17\n")).isEqualTo(withYear)
    }

    @Test
    fun leapDay() {
        assertThat(BirthdayParser.parse("1992-02-29")).isEqualTo(Birthday(MonthDay.of(2, 29), 1992))
        assertThat(BirthdayParser.parse("--02-29")).isEqualTo(Birthday(MonthDay.of(2, 29), null))
        assertThat(BirthdayParser.parse("--0229")).isEqualTo(Birthday(MonthDay.of(2, 29), null))
        assertThat(BirthdayParser.parse("1991-02-29")).isNull()
        assertThat(BirthdayParser.parse("19910229")).isNull()
    }

    @Test
    fun placeholderYearsMeanUnknownYear() {
        assertThat(BirthdayParser.parse("0000-05-17")).isEqualTo(Birthday(MonthDay.of(5, 17), null))
        assertThat(BirthdayParser.parse("1604-05-17")).isEqualTo(Birthday(MonthDay.of(5, 17), null))
        assertThat(BirthdayParser.parse("0000-02-30")).isNull()
    }

    @Test
    fun invalidInputsReturnNull() {
        listOf(
            null, "", "   ", "abc", "1990-13-01", "1990-00-10", "1990-04-31", "1990-02-30",
            "--13-01", "--02-30", "--1301", "1990517", "1990/05/17", "90-05-17", "+1990-05-17",
            "1990-05-17T00:00", "--5-17", "-05-17", "1990-5-17",
        ).forEach { raw ->
            assertThat(BirthdayParser.parse(raw)).isNull()
        }
    }

    @Test
    fun ageAroundTheBirthday() {
        val birthday = Birthday(MonthDay.of(5, 17), 1990)

        assertThat(birthday.ageOn(date("2026-05-16"))).isEqualTo(35)
        assertThat(birthday.ageOn(date("2026-05-17"))).isEqualTo(36)
        assertThat(birthday.ageOn(date("1990-05-17"))).isEqualTo(0)
        assertThat(birthday.ageOn(date("1990-05-16"))).isNull()
        assertThat(birthday.ageTurningIn(2026)).isEqualTo(36)
        assertThat(birthday.ageTurningIn(1989)).isNull()
    }

    @Test
    fun leapDayAgeCountsFeb28InNonLeapYears() {
        val birthday = Birthday(MonthDay.of(2, 29), 1992)

        assertThat(birthday.ageOn(date("2026-02-27"))).isEqualTo(33)
        assertThat(birthday.ageOn(date("2026-02-28"))).isEqualTo(34)
        assertThat(birthday.ageOn(date("2028-02-28"))).isEqualTo(35)
        assertThat(birthday.ageOn(date("2028-02-29"))).isEqualTo(36)
    }

    @Test
    fun unknownYearHasNoAge() {
        val birthday = Birthday(MonthDay.of(5, 17))

        assertThat(birthday.ageOn(date("2026-06-01"))).isNull()
        assertThat(birthday.ageTurningIn(2026)).isNull()
    }

    @Test
    fun toScheduleBuildsAnnualWithLead() {
        val schedule = Birthday(MonthDay.of(5, 17), 1990).toSchedule(LeadOffset.days(1))

        assertThat(schedule).isEqualTo(Schedule.Annual(MonthDay.of(5, 17), sinceYear = 1990, lead = LeadOffset.days(1)))
    }
}
