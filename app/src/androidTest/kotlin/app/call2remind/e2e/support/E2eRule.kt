package app.call2remind.e2e.support

import android.os.Build
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Puts the device and app into a known state around every end-to-end test:
 *
 * - before: runtime permissions + full-screen-intent app-op, onboarding done, default settings,
 *   DND off, nothing ringing, screen on and unlocked;
 * - after (always): end every active occurrence, remove test reminders and calendars, DND off,
 *   original time zone back, screen on and unlocked;
 * - on failure: dumps DB state, activities, services and our alarms to logcat (tag [E2E_TAG]),
 *   which the CI failure summary includes.
 */
class E2eRule : TestRule {
    private val cleanups = ArrayList<Pair<String, () -> Unit>>()

    /** Registers an extra restore step, run after the test (last registered runs first). */
    fun onTearDown(what: String, block: () -> Unit) {
        cleanups += what to block
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            e2eLog("=== START ${description.displayName} (API ${Build.VERSION.SDK_INT}, root=${Device.isRoot})")
            setUp()
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (e: AssumptionViolatedException) {
                e2eLog("SKIPPED ${description.displayName}: ${e.message}")
                failure = e
            } catch (e: Throwable) {
                e2eLog("FAILED ${description.displayName}: $e")
                dumpDiagnostics()
                failure = e
            } finally {
                tearDown()
            }
            e2eLog("=== END ${description.displayName}: ${if (failure == null) "passed" else failure.javaClass.simpleName}")
            failure?.let { throw it }
        }
    }

    private fun setUp() {
        Device.grantRuntimePermissions()
        Device.shell("cmd notification set_dnd off")
        AppDriver.resetSettings()
        AppDriver.cleanUp()
        Device.wakeAndUnlock()
        Device.goHome()
    }

    private fun tearDown() {
        for ((what, block) in cleanups.asReversed()) {
            runCatching(block).onFailure { e2eLog("teardown '$what' failed: $it") }
        }
        cleanups.clear()
        runCatching { Device.shell("cmd notification set_dnd off") }
        runCatching { AppDriver.cleanUp() }
        runCatching { AppDriver.resetSettings() }
        runCatching {
            Device.wakeAndUnlock()
            Device.goHome()
        }
    }

    private fun dumpDiagnostics() {
        runCatching {
            e2eLog("--- diagnostics ---")
            AppDriver.describeState().lines().forEach(::e2eLog)
            e2eLog("resumed: ${Device.resumedActivities()}")
            e2eLog("ringing service running=${Device.isRingingServiceRunning()} foreground=${Device.isRingingServiceForeground()}")
            e2eLog("interactive=${Device.isInteractive} keyguardLocked=${Device.isKeyguardLocked} fsi=${Device.canUseFullScreenIntent()}")
            e2eLog("notifications: " + AppDriver.activeNotifications().joinToString { "${it.tag}/${it.id}:${it.notification.channelId}" })
            Device.alarmDumpForApp().lines().forEach { e2eLog("alarm: $it") }
        }.onFailure { e2eLog("diagnostics failed: $it") }
    }
}
