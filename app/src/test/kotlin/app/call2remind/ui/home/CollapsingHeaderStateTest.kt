package app.call2remind.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CollapsingHeaderStateTest {
    private val header = CollapsingHeaderState().apply { maxCollapsePx = 200f }
    private val drag = NestedScrollSource.UserInput

    @Test
    fun scrollingUpCollapsesTheStripBeforeTheList() {
        val consumed = header.connection.onPreScroll(Offset(0f, -50f), drag)

        assertThat(consumed.y).isEqualTo(-50f)
        assertThat(header.offsetPx).isEqualTo(-50f)
        assertThat(header.fraction).isWithin(1e-4f).of(0.25f)
        assertThat(header.isCollapsed).isFalse()
    }

    @Test
    fun collapseStopsAtTheStripHeightAndPassesTheRestOn() {
        val consumed = header.connection.onPreScroll(Offset(0f, -260f), drag)

        assertThat(consumed.y).isEqualTo(-200f)
        assertThat(header.fraction).isEqualTo(1f)
        assertThat(header.isCollapsed).isTrue()
    }

    @Test
    fun crossingTheThresholdFlipsCollapsed() {
        header.consume(-(200f * CollapsingHeaderState.COLLAPSED_AT) + 1f)
        assertThat(header.isCollapsed).isFalse()

        header.consume(-2f)
        assertThat(header.isCollapsed).isTrue()
    }

    @Test
    fun scrollingDownOnlyExpandsWithWhatTheListLeftOver() {
        header.consume(-200f)

        // The list consumed everything (it was not at its top): the strip stays collapsed.
        assertThat(header.connection.onPreScroll(Offset(0f, 80f), drag)).isEqualTo(Offset.Zero)
        assertThat(header.fraction).isEqualTo(1f)

        // The list is at its top: the leftover expands the strip.
        val consumed = header.connection.onPostScroll(Offset.Zero, Offset(0f, 80f), drag)
        assertThat(consumed.y).isEqualTo(80f)
        assertThat(header.offsetPx).isEqualTo(-120f)
    }

    @Test
    fun aFlingThatStopsHalfWaySettlesToTheNearerEnd() = runBlocking<Unit> {
        header.consume(-60f)
        header.connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertThat(header.fraction).isEqualTo(0f)

        header.consume(-140f)
        header.connection.onPostFling(Velocity.Zero, Velocity.Zero)
        assertThat(header.fraction).isEqualTo(1f)
    }

    @Test
    fun aRestoredFractionAppliesOnceTheHeightIsKnown() {
        val restored = CollapsingHeaderState(initialFraction = 1f)
        assertThat(restored.fraction).isEqualTo(0f)

        restored.maxCollapsePx = 180f

        assertThat(restored.offsetPx).isEqualTo(-180f)
        assertThat(restored.isCollapsed).isTrue()
    }

    @Test
    fun aTallerStripKeepsTheSameFraction() {
        header.consume(-100f)

        header.maxCollapsePx = 300f

        assertThat(header.fraction).isWithin(1e-4f).of(0.5f)
    }
}
