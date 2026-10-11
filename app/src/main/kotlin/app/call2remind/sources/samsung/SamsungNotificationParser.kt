package app.call2remind.sources.samsung

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The parts of a posted `StatusBarNotification` the Samsung listener needs, extracted so parsing
 * stays pure and testable. Text fields come from `Notification.extras`.
 */
data class PostedNotification(
    val key: String,
    val packageName: String,
    val postTime: Long,
    val category: String? = null,
    val channelId: String? = null,
    val isGroupSummary: Boolean = false,
    /** `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`. */
    val isOngoing: Boolean = false,
    val title: String? = null,
    val bigTitle: String? = null,
    val text: String? = null,
    val bigText: String? = null,
)

/** A Samsung reminder notification recognized by [SamsungNotificationParser]. */
data class SamsungReminderNotification(
    val key: String,
    val title: String,
    val notes: String?,
    val postedAt: Instant,
) {
    /** `"<notification key>@<postTime millis>"`: a re-post of the same key later is a new ring. */
    val externalId: String get() = "$key@${postedAt.toEpochMilli()}"

    fun toReminder(zone: ZoneId): Reminder = Reminder(
        id = ReminderIds.of(SourceType.SAMSUNG_REMINDER, externalId),
        sourceType = SourceType.SAMSUNG_REMINDER,
        externalId = externalId,
        title = title,
        schedule = Schedule.At(postedAt),
        zone = zone,
        notes = notes,
    )
}

/**
 * Decides whether a posted notification is a Samsung Reminders alert and extracts it. Defensive,
 * because Samsung's channel ids and categories are undocumented and vary by version:
 *
 * - Package must be in [packages].
 * - Ignored: group summaries, ongoing / foreground-service notifications, categories that are
 *   never a reminder (progress, service, status, system, error, transport, call, message, email,
 *   social, promo, recommendation, navigation, location sharing), and channels whose id looks
 *   technical (sync, backup, update, progress, service, foreground, silent, summary, badge).
 * - Everything else with a non-blank title (or, failing that, text) is a reminder — categories
 *   `reminder` / `alarm` / `event` and unknown ones alike.
 * - Title: `EXTRA_TITLE` → `EXTRA_TITLE_BIG` → text. Notes: big text → text, unless equal to the title.
 */
class SamsungNotificationParser(private val packages: Set<String>) {

    fun parse(notification: PostedNotification): SamsungReminderNotification? {
        if (notification.packageName !in packages) return null
        if (notification.isGroupSummary || notification.isOngoing) return null
        if (notification.category in IGNORED_CATEGORIES) return null
        val channel = notification.channelId
        if (channel != null && IGNORED_CHANNEL.containsMatchIn(channel)) return null

        val title = firstNonBlank(notification.title, notification.bigTitle, notification.text, notification.bigText)
            ?: return null
        val notes = firstNonBlank(notification.bigText, notification.text)?.takeIf { it != title }
        return SamsungReminderNotification(
            key = notification.key,
            title = title,
            notes = notes,
            postedAt = Instant.ofEpochMilli(notification.postTime),
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }

    companion object {
        /** `Notification.CATEGORY_*` values that are never a reminder. */
        val IGNORED_CATEGORIES: Set<String> = setOf(
            "progress", "service", "status", "sys", "err", "transport", "call", "msg", "email",
            "social", "promo", "recommendation", "navigation", "location_sharing", "missed_call",
        )
        private val IGNORED_CHANNEL = Regex(
            "sync|backup|update|progress|service|foreground|silent|summary|badge",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * In-memory dedupe for the listener: Samsung re-posts (updates) the same notification key, e.g.
 * to refresh actions. A key is handled at most once per [window], and the exact same
 * `(key, postTime)` never twice while remembered. Bounded to [maxEntries]. Thread-safe.
 */
class SamsungNotificationDeduper(
    private val window: Duration = DEFAULT_WINDOW,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Seen(val postedAt: Instant, val handledAt: Instant)

    private val seen = LinkedHashMap<String, Seen>()

    /** True if [key] posted at [postedAt] should ring now; records it if so. */
    @Synchronized
    fun tryAcquire(key: String, postedAt: Instant, now: Instant): Boolean {
        val previous = seen[key]
        if (previous != null) {
            if (previous.postedAt == postedAt) return false
            if (Duration.between(previous.handledAt, now) < window) return false
        }
        seen.remove(key)
        seen[key] = Seen(postedAt, now)
        while (seen.size > maxEntries) {
            val eldest = seen.keys.first()
            seen.remove(eldest)
        }
        return true
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(2)
        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}
