package app.call2remind.core.model

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration

class LeadOffsetTest {

    @Test
    fun noneLeavesInstantUnchanged() {
        val base = at("2026-05-01T10:00", 5, 30)

        assertThat(LeadOffset.NONE.applyTo(base, KOLKATA)).isEqualTo(base)
        assertThat(LeadOffset.NONE.isNone).isTrue()
    }

    @Test
    fun minutesSubtractExactDuration() {
        val base = at("2026-05-01T10:00", 5, 30)

        assertThat(LeadOffset.minutes(15).applyTo(base, KOLKATA)).isEqualTo(at("2026-05-01T09:45", 5, 30))
    }

    @Test
    fun daysKeepWallClockTimeAcrossDst() {
        // Mon Mar 9 09:00 EDT → Sun Mar 8 09:00, which is already EDT (switch was at 02:00): 24 h.
        val base = at("2026-03-09T09:00", -4)
        val fire = LeadOffset.days(1).applyTo(base, NEW_YORK)

        assertThat(fire).isEqualTo(at("2026-03-08T09:00", -4))
        assertThat(Duration.between(fire, base)).isEqualTo(Duration.ofHours(24))

        // Across the gap itself: Mar 8 09:00 EDT → Mar 7 09:00 EST is 23 h earlier.
        val sundayBase = at("2026-03-08T09:00", -4)
        val saturdayFire = LeadOffset.days(1).applyTo(sundayBase, NEW_YORK)
        assertThat(saturdayFire).isEqualTo(at("2026-03-07T09:00", -5))
        assertThat(Duration.between(saturdayFire, sundayBase)).isEqualTo(Duration.ofHours(23))
    }

    @Test
    fun eventTimeForInvertsApplyTo() {
        val lead = LeadOffset(days = 1, duration = Duration.ofMinutes(30))
        val base = at("2026-03-08T09:00", -4)

        assertThat(lead.eventTimeFor(lead.applyTo(base, NEW_YORK), NEW_YORK)).isEqualTo(base)
    }

    @Test
    fun maxSpanIsAnUpperBound() {
        assertThat(LeadOffset(days = 2, duration = Duration.ofMinutes(10)).maxSpan)
            .isEqualTo(Duration.ofHours(50).plusMinutes(10))
    }

    @Test
    fun rejectsNegativeParts() {
        assertThrows(IllegalArgumentException::class.java) { LeadOffset(days = -1) }
        assertThrows(IllegalArgumentException::class.java) { LeadOffset(duration = Duration.ofMinutes(-1)) }
    }
}
