package app.call2remind.e2e.support

import android.os.SystemClock

/** Polling waits with a clear failure message (the only way to wait in these tests). */
object Waits {
    /** Polls [condition] every [pollMs] until true; fails after [timeoutMs] naming [what]. */
    fun until(what: String, timeoutMs: Long, pollMs: Long = 250, condition: () -> Boolean) {
        value(what, timeoutMs, pollMs) { if (condition()) Unit else null }
    }

    /** Polls [block] until it returns non-null and returns that; fails after [timeoutMs]. */
    fun <T : Any> value(what: String, timeoutMs: Long, pollMs: Long = 250, block: () -> T?): T {
        val start = SystemClock.uptimeMillis()
        val deadline = start + timeoutMs
        var lastError: Throwable? = null
        while (true) {
            try {
                val result = block()
                if (result != null) {
                    val took = SystemClock.uptimeMillis() - start
                    if (took > 1_000) e2eLog("waited ${took}ms for $what")
                    return result
                }
            } catch (e: Exception) {
                lastError = e
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what", lastError)
            }
            SystemClock.sleep(pollMs)
        }
    }

    /** Like [value] but returns null instead of failing. */
    fun <T : Any> valueOrNull(timeoutMs: Long, pollMs: Long = 250, block: () -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            runCatching(block).getOrNull()?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(pollMs)
        }
    }

    /** Like [until] but returns false instead of failing. */
    fun within(timeoutMs: Long, pollMs: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (runCatching(condition).getOrDefault(false)) return true
            if (SystemClock.uptimeMillis() >= deadline) return false
            SystemClock.sleep(pollMs)
        }
    }
}
