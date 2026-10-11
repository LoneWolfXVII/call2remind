package app.call2remind.e2e.support

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.call2remind.R

/**
 * The incoming-call screen's controls, operated two ways: through accessibility (TalkBack-style
 * actions: the Answer click and the Decline custom action) and by touch (dragging the plug into
 * the socket, tapping the pill buttons) like a sighted user.
 */
object CallScreenDriver {
    private fun string(id: Int, vararg args: Any): String = Device.context.getString(id, *args)

    val answerLabel: String get() = string(R.string.action_answer)
    val declineLabel: String get() = string(R.string.action_decline)
    val doneLabel: String get() = string(R.string.action_done)
    val noRingBacksLeft: String get() = string(R.string.call_decline_to_missed)
    fun snoozeLabel(minutes: Long): String = string(R.string.call_snooze_minutes, minutes.toInt())

    /** Drags the plug from its rest position into the socket (touch). */
    fun answerByDrag() {
        val track = Device.ui.wait(Until.findObject(By.pkg(PKG).desc(answerLabel)), 10_000)
            ?: throw AssertionError("answer track (desc '$answerLabel') not found")
        val b = track.visibleBounds
        val density = Device.context.resources.displayMetrics.density
        val y = b.centerY()
        val startX = (b.left + 40 * density).toInt()
        val endX = (b.right - 30 * density).toInt()
        e2eLog("dragging the plug from ($startX,$y) to ($endX,$y) in $b")
        check(Device.ui.drag(startX, y, endX, y, 30)) { "plug drag was not injected" }
    }

    /** Taps the pill button labelled [text] (touch). */
    fun tap(text: String, timeoutMs: Long = 10_000) {
        val button = Device.ui.wait(Until.findObject(By.pkg(PKG).text(text)), timeoutMs)
            ?: throw AssertionError("button '$text' not found")
        button.click()
        e2eLog("tapped '$text'")
    }
}
