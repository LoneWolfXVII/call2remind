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

    /**
     * Waits for a node with content description [desc] and performs its accessibility click,
     * retrying briefly (Compose may refresh the node). Returns false if every attempt was refused
     * (the node and the whole tree are logged).
     */
    fun tryClickDescription(desc: String, timeoutMs: Long = 15_000): Boolean {
        val node = Waits.value("node with description '$desc'", timeoutMs) { byDescription(desc) }
        return perform("click on '$desc'", node, AccessibilityNodeInfo.ACTION_CLICK) { byDescription(desc) }
    }

    fun clickDescription(desc: String, timeoutMs: Long = 15_000) {
        check(tryClickDescription(desc, timeoutMs)) { "ACTION_CLICK on '$desc' was refused (see $E2E_TAG log for the node tree)" }
    }

    /** Waits for a (clickable) node with text [text] and performs its accessibility click. */
    fun clickText(text: String, timeoutMs: Long = 15_000) {
        val find = { byText(text)?.let { clickableSelfOrAncestor(it) } }
        val node = Waits.value("clickable node with text '$text'", timeoutMs) { find() }
        check(perform("click on '$text'", node, AccessibilityNodeInfo.ACTION_CLICK, find)) {
            "ACTION_CLICK on '$text' was refused (see $E2E_TAG log for the node tree)"
        }
    }

    /**
     * Performs the custom accessibility action labelled [label] on the node described [desc].
     * Returns false if the node never offered it or refused it (logged with the tree).
     */
    fun tryCustomAction(desc: String, label: String, timeoutMs: Long = 15_000): Boolean {
        val find = { byDescription(desc)?.takeIf { n -> n.actionList.any { it.label?.toString() == label } } }
        val node = Waits.valueOrNull(timeoutMs) { find() }
        if (node == null) {
            e2eLog("A11Y: no custom action '$label' on '$desc': ${byDescription(desc)?.let(::describe)}")
            dumpTree()
            return false
        }
        val id = node.actionList.first { it.label?.toString() == label }.id
        return perform("custom action '$label' on '$desc'", node, id) { find() }
    }

    private fun perform(what: String, first: AccessibilityNodeInfo, action: Int, refind: () -> AccessibilityNodeInfo?): Boolean {
        var node: AccessibilityNodeInfo? = first
        repeat(3) { attempt ->
            val current = node
            if (current != null && current.performAction(action)) {
                e2eLog("a11y $what (attempt ${attempt + 1})")
                return true
            }
            e2eLog("A11Y: $what refused (attempt ${attempt + 1}): ${current?.let(::describe)}")
            Thread.sleep(1_000)
            node = refind()
        }
        dumpTree()
        return false
    }

    fun describe(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return "${node.className} text='${node.text ?: ""}' desc='${node.contentDescription ?: ""}' " +
            "enabled=${node.isEnabled} clickable=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds " +
            "actions=[${node.actionList.joinToString { "${it.id}:${it.label ?: ""}" }}]"
    }

    /** Logs every node of our windows (for diagnosing accessibility failures). */
    fun dumpTree() {
        val ua = automation
        val windows = runCatching { ua.windows }.getOrDefault(emptyList())
        e2eLog("A11Y tree: ${windows.size} windows: " + windows.joinToString { "${it.title}/${it.root?.packageName}" })
        for (root in roots()) {
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.add(root to 0)
            var count = 0
            while (stack.isNotEmpty() && count < 200) {
                val (node, depth) = stack.removeLast()
                count++
                e2eLog("A11Y " + "  ".repeat(depth) + describe(node))
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it to depth + 1) }
            }
        }
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
