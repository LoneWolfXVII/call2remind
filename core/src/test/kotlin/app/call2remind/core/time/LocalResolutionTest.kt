package app.call2remind.core.time

import app.call2remind.core.Fixtures.KOLKATA
import app.call2remind.core.Fixtures.LONDON
import app.call2remind.core.Fixtures.NEW_YORK
import app.call2remind.core.Fixtures.at
import app.call2remind.core.Fixtures.date
import app.call2remind.core.Fixtures.time
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalResolutionTest {

    @Test
    fun gapShiftsForward() {
        assertThat(resolveLocal(date("2026-03-08"), time("02:30"), NEW_YORK)).isEqualTo(at("2026-03-08T03:30", -4))
        assertThat(resolveLocal(date("2026-03-29"), time("01:15"), LONDON)).isEqualTo(at("2026-03-29T02:15", 1))
    }

    @Test
    fun overlapUsesEarlierOffset() {
        assertThat(resolveLocal(date("2026-11-01"), time("01:30"), NEW_YORK)).isEqualTo(at("2026-11-01T01:30", -4))
        assertThat(resolveLocal(date("2026-10-25"), time("01:30"), LONDON)).isEqualTo(at("2026-10-25T01:30", 1))
    }

    @Test
    fun kolkataIsAlwaysPlusFiveThirty() {
        assertThat(resolveLocal(date("2026-03-08"), time("02:30"), KOLKATA)).isEqualTo(at("2026-03-08T02:30", 5, 30))
        assertThat(resolveLocal(date("2026-11-01"), time("01:30"), KOLKATA)).isEqualTo(at("2026-11-01T01:30", 5, 30))
    }
}
