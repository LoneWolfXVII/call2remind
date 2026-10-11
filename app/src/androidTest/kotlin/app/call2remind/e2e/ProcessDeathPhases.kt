package app.call2remind.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.call2remind.core.log.RingLogType
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Calls
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.e2eLog
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

/** Tests only run by `scripts/emulator-e2e.sh` in separate instrumentation runs (excluded from the main suite). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ProcessDeathPhase

/**
 * "The process died while a ring was pending" — the OEM task killer / low-memory case. The app
 * process cannot kill itself from inside its own instrumentation, so the shell drives it:
 *
 * 1. `arm`: store a call ~75 s out through the engine and record it in a file.
 * 2. shell: the instrumentation ends (its process is killed; alarms survive — this is NOT a
 *    force-stop), the script makes sure the process is dead, turns the screen off and watches
 *    `dumpsys` for the ringing service and call screen at fire time.
 * 3. `verify`: the ring log shows the alarm fired on time (a late FIRED would mean the ring only
 *    happened because this instrumentation restarted the app), and the shell saw it ring.
 */
@ProcessDeathPhase
@LargeTest
@RunWith(AndroidJUnit4::class)
class ProcessDeathPhases {
    private val args = InstrumentationRegistry.getArguments()
    private val stateFile: File get() = File(Device.context.filesDir, STATE_FILE)

    @Test
    fun arm() {
        assumeTrue("run by scripts/emulator-e2e.sh", args.getString("e2ePhase") == "arm")
        Device.grantRuntimePermissions()
        Device.shell("cmd notification set_dnd off")
        AppDriver.resetSettings()
        AppDriver.cleanUp()
        val call = AppDriver.scheduleCall("process death", lead = Duration.ofSeconds(LEAD_SECONDS))
        assertThat(Device.pendingAppAlarmCount()).isAtLeast(1)
        stateFile.writeText("${call.occurrenceId}\n${call.fireAt.toEpochMilli()}\n${call.title}\n")
        e2eLog("process-death arm: ${call.occurrenceId} fires at ${call.fireAt}")
    }

    @Test
    fun verify() {
        assumeTrue("run by scripts/emulator-e2e.sh", args.getString("e2ePhase") == "verify")
        val (id, fireAtMillis, title) = stateFile.readLines()
        val fireAt = Instant.ofEpochMilli(fireAtMillis.toLong())
        try {
            val log = AppDriver.ringLog(id)
            e2eLog("process-death verify: '$title' log=${log.map { "${it.type}@${it.timestamp}" }}")
            val fired = log.firstOrNull { it.type == RingLogType.FIRED }
            assertWithMessage("the alarm rang the call after the process died (FIRED in ring log)").that(fired).isNotNull()
            val late = Duration.between(fireAt, fired!!.timestamp)
            assertWithMessage("rang on its own alarm, on time (FIRED ${late.seconds}s after fire time)")
                .that(late).isAtMost(Calls.RING_LATENESS)
            assertWithMessage("shell saw the ringing service in the foreground")
                .that(args.getString("observedService")).isEqualTo("1")
            assertWithMessage("shell saw the full-screen call screen resumed")
                .that(args.getString("observedScreen")).isEqualTo("1")
        } finally {
            AppDriver.cleanUp()
            stateFile.delete()
            Device.wakeAndUnlock()
        }
    }

    private companion object {
        const val STATE_FILE = "e2e_process_death.txt"
        const val LEAD_SECONDS = 75L
    }
}
