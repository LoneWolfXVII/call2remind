package app.call2remind.ui.components

import app.call2remind.ui.components.SwipePhysics.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwipePhysicsTest {
    private val threshold = SwipePhysics.threshold(widthPx = 1_000f, maxPx = 300f)
    private val fling = 2_000f

    @Test
    fun thresholdIsAThirdOfTheRowCapped() {
        assertThat(SwipePhysics.threshold(600f, 1_000f)).isWithin(0.01f).of(204f)
        assertThat(threshold).isEqualTo(300f)
    }

    @Test
    fun pastTheThresholdCommitsInItsDirection() {
        assertThat(SwipePhysics.release(320f, 0f, threshold, fling)).isEqualTo(Outcome.START)
        assertThat(SwipePhysics.release(-320f, 0f, threshold, fling)).isEqualTo(Outcome.END)
    }

    @Test
    fun shortOfItSpringsBack() {
        assertThat(SwipePhysics.release(120f, 300f, threshold, fling)).isEqualTo(Outcome.BACK)
        assertThat(SwipePhysics.release(-120f, -300f, threshold, fling)).isEqualTo(Outcome.BACK)
    }

    @Test
    fun aFastFlingCommitsEarly() {
        assertThat(SwipePhysics.release(60f, 2_500f, threshold, fling)).isEqualTo(Outcome.START)
        assertThat(SwipePhysics.release(-60f, -2_500f, threshold, fling)).isEqualTo(Outcome.END)
    }

    @Test
    fun flingingBackCancelsEvenPastTheThreshold() {
        assertThat(SwipePhysics.release(320f, -2_500f, threshold, fling)).isEqualTo(Outcome.BACK)
    }

    @Test
    fun noWidthNeverCommits() {
        assertThat(SwipePhysics.release(500f, 5_000f, 0f, fling)).isEqualTo(Outcome.BACK)
    }
}
