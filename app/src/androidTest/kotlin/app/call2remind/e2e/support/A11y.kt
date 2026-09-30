package app.call2remind.e2e.support

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives our screens through the accessibility tree, the way TalkBack / Switch Access would:
 * the call screen's answer control is a drag gesture for touch, but exposes an accessible
 * "Answer" click and a "Decline" custom action. Searches every interactive window (the call
 * screen sits over the keyguard) and only nodes of our package.
 */
object A11y {
    private val automation: UiAutomation
        get() = Device.instrumentation.uiAutomation.also { ua ->
            val info = ua.serviceInfo
            val wanted = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            if (info != null && info.flags and wanted != wanted) {
                info.flags = info.flags or wanted
                ua.serviceInfo = info
            }
        }

    private fun roots(): List<AccessibilityNodeInfo> {
        val ua = automation
        val fromWindows = runCatching { ua.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return (fromWindows + listOfNotNull(ua.rootInActiveWindow)).filter { it.packageName == PKG }
    }

    fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (root in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    fun byDescription(desc: String): AccessibilityNodeInfo? = find { it.contentDescription?.toString() == desc }

    fun byText(text: String): AccessibilityNodeInfo? = find { it.text?.toString() == text }

    fun isShown(text: String): Boolean = byText(text) != null || byDescription(text) != null

    /** Waits for a node with content description [desc] and performs its accessibility click. */
    fun clickDescription(desc: String, timeoutMs: Long = 15_000) {
        val node = Waits.value("node with description '$desc'", timeoutMs) { byDescription(desc) }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ACTION_CLICK on '$desc' was refused" }
        e2eLog("a11y click on '$desc'")
    }

    /** Waits for a (clickable) node with text [text] and performs its accessibility click. */
    fun clickText(text: String, timeoutMs: Long = 15_000) {
        val node = Waits.value("clickable node with text '$text'", timeoutMs) {
            byText(text)?.let { clickableSelfOrAncestor(it) }
        }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ACTION_CLICK on '$text' was refused" }
        e2eLog("a11y click on '$text'")
    }

    /** Performs the custom accessibility action labelled [label] on the node described [desc]. */
    fun customAction(desc: String, label: String, timeoutMs: Long = 15_000) {
        val (node, action) = Waits.value("custom action '$label' on '$desc'", timeoutMs) {
            byDescription(desc)?.let { node ->
                node.actionList.firstOrNull { it.label?.toString() == label }?.let { node to it }
            }
        }
        check(node.performAction(action.id)) { "custom action '$label' was refused" }
        e2eLog("a11y custom action '$label' on '$desc'")
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return current
            current = current.parent
        }
        return null
    }
}
