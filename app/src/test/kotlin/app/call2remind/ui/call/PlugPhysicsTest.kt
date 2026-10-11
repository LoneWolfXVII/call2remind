package app.call2remind.ui.call

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlugPhysicsTest {
    private val max = 300f
    private val overshoot = 14f

    @Test
    fun resistanceGrowsTowardsTheSocket() {
        val early = PlugPhysics.drag(0f, 10f, max, overshoot) - 0f
        val late = PlugPhysics.drag(270f, 10f, max, overshoot) - 270f
        assertThat(early).isWithin(0.001f).of(10f)
        assertThat(late).isLessThan(early)
        assertThat(late).isGreaterThan(0f)
    }

    @Test
    fun pastTheEndsItRubberBandsAndClamps() {
        assertThat(PlugPhysics.drag(max, 10f, max, overshoot)).isWithin(0.001f).of(max + 10f * PlugPhysics.OVERSCROLL)
        assertThat(PlugPhysics.drag(0f, -10f, max, overshoot)).isWithin(0.001f).of(-10f * PlugPhysics.OVERSCROLL)
        assertThat(PlugPhysics.drag(max, 10_000f, max, overshoot)).isEqualTo(max + overshoot)
        assertThat(PlugPhysics.drag(0f, -10_000f, max, overshoot)).isEqualTo(-overshoot)
    }

    @Test
    fun draggingBackIsNotResisted() {
        assertThat(PlugPhysics.drag(200f, -50f, max, overshoot)).isWithin(0.001f).of(150f)
    }

    @Test
    fun commitNeedsEightyPercentOrAFling() {
        val fling = 1_000f
        assertThat(PlugPhysics.commits(0.79f * max, 0f, max, fling)).isFalse()
        assertThat(PlugPhysics.commits(0.8f * max, 0f, max, fling)).isTrue()
        assertThat(PlugPhysics.commits(0.5f * max, 1_200f, max, fling)).isTrue()
        assertThat(PlugPhysics.commits(0.3f * max, 5_000f, max, fling)).isFalse()
        assertThat(PlugPhysics.commits(100f, 0f, 0f, fling)).isFalse()
    }

    @Test
    fun detentsAtQuarterSteps() {
        assertThat(PlugPhysics.detent(0.2f * max, max)).isEqualTo(0)
        assertThat(PlugPhysics.detent(0.25f * max, max)).isEqualTo(1)
        assertThat(PlugPhysics.detent(0.5f * max, max)).isEqualTo(2)
        assertThat(PlugPhysics.detent(0.76f * max, max)).isEqualTo(3)
        assertThat(PlugPhysics.detent(1.2f * max, max)).isEqualTo(3)
    }

    @Test
    fun wordRangesSplitOnWhitespace() {
        assertThat(wordRanges("Reminder: Pay rent.  Now")).containsExactly(0..8, 10..12, 14..18, 21..23).inOrder()
        assertThat(wordRanges("")).isEmpty()
    }
}
