package app.call2remind.e2e.support

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.time.ZoneId

/** Log tag of everything the end-to-end tests print (the CI failure summary greps for it). */
const val E2E_TAG = "C2R-E2E"

const val PKG = "app.call2remind"

fun e2eLog(message: String) {
    Log.i(E2E_TAG, message)
}

/**
 * Device-level controls for the end-to-end tests: shell commands (through UiAutomation, so as the
 * shell user, or root when adbd runs as root), screen, Do Not Disturb, time zone and dumpsys.
 *
 * Note: `executeShellCommand` does not run a shell — no pipes, redirects or quoting.
 */
object Device {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val ui: UiDevice get() = UiDevice.getInstance(instrumentation)

    private val power get() = context.getSystemService(PowerManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** True when shell commands run as root (`adb root` on a google_apis emulator). */
    val isRoot: Boolean by lazy { shell("id").contains("uid=0(") }

    // --- permissions -----------------------------------------------------------------------

    fun grantRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            add("android.permission.READ_CALENDAR")
            add("android.permission.READ_CONTACTS")
        }
        permissions.forEach { shell("pm grant $PKG $it") }
        if (Build.VERSION.SDK_INT >= 34) {
            shell("appops set $PKG USE_FULL_SCREEN_INTENT allow")
        }
        // Battery optimisations never hold back setAlarmClock, but keep the emulator deterministic.
        shell("cmd deviceidle whitelist +$PKG")
    }

    fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()

    // --- screen ----------------------------------------------------------------------------

    val isInteractive: Boolean get() = power.isInteractive
    val isKeyguardLocked: Boolean get() = keyguard.isKeyguardLocked

    /** Turns the screen off (the keyguard locks) and waits until it is off. */
    fun sleepScreen() {
        shell("input keyevent KEYCODE_SLEEP")
        Waits.until("screen off", 10_000) { !isInteractive }
        // Let the keyguard settle so the ring really arrives on a locked, dark screen.
        Thread.sleep(1_000)
        e2eLog("screen off, keyguardLocked=$isKeyguardLocked")
    }

    fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Waits.until("screen on", 10_000) { isInteractive }
    }

    fun goHome() {
        shell("input keyevent KEYCODE_HOME")
    }

    // --- dumpsys ---------------------------------------------------------------------------

    fun resumedActivities(): List<String> =
        shell("dumpsys activity activities").lines().filter { it.contains("ResumedActivity") }

    fun isCallActivityResumed(): Boolean = resumedActivities().any { it.contains("IncomingCallActivity") }

    fun isCallActivityAlive(): Boolean =
        shell("dumpsys activity activities").lines().any { it.contains("ActivityRecord{") && it.contains("IncomingCallActivity") }

    private fun ringingServiceDump(): String = shell("dumpsys activity services $PKG/.ringing.RingingService")

    fun isRingingServiceRunning(): Boolean = ringingServiceDump().contains("ServiceRecord{")

    fun isRingingServiceForeground(): Boolean = ringingServiceDump().contains("isForeground=true")

    /**
     * Pending alarms of our alarm receiver in `dumpsys alarm`: blocks that start with
     * `<TYPE> #n: Alarm{` and mention our fire action (statistics sections have no such blocks).
     */
    fun pendingAppAlarmCount(): Int = appAlarmTimes().size

    /**
     * Trigger times (epoch millis, the `origWhen` of each `dumpsys alarm` entry) of this app's
     * pending occurrence alarms; -1 for an entry whose header has no parsable `origWhen`.
     */
    fun appAlarmTimes(): List<Long> {
        val header = Regex("""^(\s*)(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED|ELAPSED_REALTIME_WAKEUP|ELAPSED_REALTIME) #\d+: Alarm\{""")
        val origWhen = Regex("""origWhen (-?\d+)""")
        val times = mutableListOf<Long>()
        var blockIndent = -1
        var blockWhen = -1L
        var matched = false
        fun close() {
            if (blockIndent >= 0 && matched) times += blockWhen
            blockIndent = -1
            matched = false
        }
        for (line in shell("dumpsys alarm").lines()) {
            val m = header.find(line)
            if (m != null) {
                close()
                blockIndent = m.groupValues[1].length
                blockWhen = origWhen.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                continue
            }
            if (blockIndent < 0 || line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent <= blockIndent) {
                close()
            } else if (line.contains("app.call2remind.action.ALARM_FIRE")) {
                matched = true
            }
        }
        close()
        return times
    }

    fun alarmDumpForApp(): String =
        shell("dumpsys alarm").lines().filter { it.contains("Alarm{") && it.contains(PKG) }.take(12).joinToString("\n")

    // --- Do Not Disturb --------------------------------------------------------------------

    /** `cmd notification set_dnd <mode>`: off | priority | alarms | on (total silence). */
    fun setDnd(mode: String) {
        shell("cmd notification set_dnd $mode")
        val expected = when (mode) {
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> NotificationManager.INTERRUPTION_FILTER_NONE
        }
        Waits.until("DND filter $mode", 10_000) { notifications.currentInterruptionFilter == expected }
        e2eLog("DND set to $mode (filter=${notifications.currentInterruptionFilter})")
    }

    // --- time zone -------------------------------------------------------------------------

    fun deviceTimeZone(): String = shell("getprop persist.sys.timezone").trim()

    /**
     * Sets the device time zone the way the system does (AlarmManager → TIMEZONE_CHANGED
     * broadcast) and waits until this process sees it. Automatic zone detection is turned off
     * first. Returns false if the zone could not be changed.
     */
    fun setTimeZone(id: String): Boolean {
        shell("settings put global auto_time_zone 0")
        shell("cmd time_zone_detector set_auto_detection_enabled false")
        shell("cmd alarm set-timezone $id")
        if (deviceTimeZone() != id) {
            e2eLog("cmd alarm set-timezone did not apply; trying AlarmManager with shell identity")
            val ua = instrumentation.uiAutomation
            try {
                ua.adoptShellPermissionIdentity("android.permission.SET_TIME_ZONE")
                alarms.setTimeZone(id)
            } catch (e: Exception) {
                e2eLog("AlarmManager.setTimeZone failed: $e")
            } finally {
                ua.dropShellPermissionIdentity()
            }
        }
        if (deviceTimeZone() != id) return false
        return try {
            Waits.until("process default zone $id", 15_000) { ZoneId.systemDefault().id == id }
            true
        } catch (e: AssertionError) {
            e2eLog("device zone is $id but the process still sees ${ZoneId.systemDefault()}")
            false
        }
    }
}
