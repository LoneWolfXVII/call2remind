package app.call2remind.sources.mstodo

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Resolves Microsoft Graph `dateTimeTimeZone` values. Graph uses Windows zone names
 * ("India Standard Time") or IANA ids, and `dateTime` strings with up to 7 fractional digits
 * ("2026-03-10T09:00:00.0000000"), without an offset.
 */
object GraphTime {
    /** Windows → IANA for common zones (CLDR windowsZones.xml, territory "001"). */
    private val WINDOWS_ZONES: Map<String, String> = mapOf(
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "Romance Standard Time" to "Europe/Paris",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "GTB Standard Time" to "Europe/Bucharest",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Russian Standard Time" to "Europe/Moscow",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Egypt Standard Time" to "Africa/Cairo",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Arab Standard Time" to "Asia/Riyadh",
        "Arabian Standard Time" to "Asia/Dubai",
        "Iran Standard Time" to "Asia/Tehran",
        "Afghanistan Standard Time" to "Asia/Kabul",
        "Pakistan Standard Time" to "Asia/Karachi",
        "West Asia Standard Time" to "Asia/Tashkent",
        "India Standard Time" to "Asia/Kolkata",
        "Sri Lanka Standard Time" to "Asia/Colombo",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "Myanmar Standard Time" to "Asia/Yangon",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Taipei Standard Time" to "Asia/Taipei",
        "W. Australia Standard Time" to "Australia/Perth",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Alaskan Standard Time" to "America/Anchorage",
        "Pacific Standard Time" to "America/Los_Angeles",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time" to "America/Denver",
        "Central Standard Time" to "America/Chicago",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Canada Central Standard Time" to "America/Regina",
        "Eastern Standard Time" to "America/New_York",
        "SA Pacific Standard Time" to "America/Bogota",
        "Atlantic Standard Time" to "America/Halifax",
        "Newfoundland Standard Time" to "America/St_Johns",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
    )

    private val CASE_INSENSITIVE: Map<String, String> = WINDOWS_ZONES.mapKeys { it.key.lowercase() }

    /** Zone for a Graph `timeZone` value: Windows name, IANA id or offset; unknown/blank → UTC. */
    fun zone(name: String?): ZoneId {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return ZoneOffset.UTC
        CASE_INSENSITIVE[trimmed.lowercase()]?.let { return if (it == "UTC") ZoneOffset.UTC else ZoneId.of(it) }
        return try {
            ZoneId.of(trimmed).normalized()
        } catch (e: DateTimeException) {
            ZoneOffset.UTC
        }
    }

    /**
     * The instant of a Graph `dateTime` in [timeZone]. Accepts 0–9 fractional digits; an explicit
     * offset / `Z` in the string wins over [timeZone]. Returns `null` if unparseable.
     */
    fun instant(dateTime: String?, timeZone: String?): Instant? {
        val text = dateTime?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDateTime.parse(text).atZone(zone(timeZone)).toInstant()
        } catch (e: DateTimeException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e2: DateTimeException) {
                null
            }
        }
    }
}
