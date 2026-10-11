package app.call2remind.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The system clock in the device's **current** time zone.
 *
 * `Clock.systemDefaultZone()` captures the default zone once, when it is created; a process-wide
 * singleton made at process start would keep the zone the phone had then, so after travelling
 * (TIMEZONE_CHANGED) new habits, date-only / annual expansion and spoken times would still use
 * the old zone until the process died. This clock reads [zoneSource] on every [getZone] call.
 *
 * [withZone] returns an ordinary fixed-zone system clock (the caller asked for a specific zone).
 */
class DeviceClock(
    private val zoneSource: () -> ZoneId = ZoneId::systemDefault,
) : Clock() {
    override fun getZone(): ZoneId = zoneSource()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()

    override fun toString(): String = "DeviceClock[${getZone()}]"
}
