package app.call2remind.sources.samsung

import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SamsungNotificationParserTest {
    private val pkg = "com.samsung.android.app.reminder"
    private val parser = SamsungNotificationParser(setOf(pkg))
    private val postTime = Instant.parse("2026-03-10T08:00:00Z").toEpochMilli()

    private fun posted(
        packageName: String = pkg,
        category: String? = "reminder",
        channelId: String? = "reminder_alert",
        title: String? = "Buy milk",
        text: String? = "On the way home",
        bigText: String? = null,
        bigTitle: String? = null,
        summary: Boolean = false,
        ongoing: Boolean = false,
    ) = PostedNotification(
        key = "0|$pkg|42|null|10123",
        packageName = packageName,
        postTime = postTime,
        category = category,
        channelId = channelId,
        isGroupSummary = summary,
        isOngoing = ongoing,
        title = title,
        bigTitle = bigTitle,
        text = text,
        bigText = bigText,
    )

    @Test
    fun parsesAReminderNotification() {
        val parsed = parser.parse(posted())

        assertThat(parsed).isEqualTo(
            SamsungReminderNotification(
                key = "0|$pkg|42|null|10123",
                title = "Buy milk",
                notes = "On the way home",
                postedAt = Instant.ofEpochMilli(postTime),
            ),
        )
        assertThat(parsed?.externalId).isEqualTo("0|$pkg|42|null|10123@$postTime")
    }

    @Test
    fun buildsAnImmediateReminder() {
        val zone = ZoneId.of("Asia/Seoul")
        val reminder = requireNotNull(parser.parse(posted())).toReminder(zone)

        assertThat(reminder.sourceType).isEqualTo(SourceType.SAMSUNG_REMINDER)
        assertThat(reminder.id).isEqualTo(ReminderIds.of(SourceType.SAMSUNG_REMINDER, reminder.externalId))
        assertThat(reminder.schedule).isEqualTo(Schedule.At(Instant.ofEpochMilli(postTime)))
        assertThat(reminder.zone).isEqualTo(zone)
        assertThat(reminder.notes).isEqualTo("On the way home")
    }

    @Test
    fun alarmEventAndUnknownCategoriesAreAccepted() {
        assertThat(parser.parse(posted(category = "alarm"))).isNotNull()
        assertThat(parser.parse(posted(category = "event"))).isNotNull()
        assertThat(parser.parse(posted(category = null, channelId = null))).isNotNull()
        assertThat(parser.parse(posted(category = "something_new", channelId = "123"))).isNotNull()
    }

    @Test
    fun otherPackagesAreIgnored() {
        assertThat(parser.parse(posted(packageName = "com.whatsapp"))).isNull()
        val custom = SamsungNotificationParser(setOf("com.example.reminders"))
        assertThat(custom.parse(posted(packageName = "com.example.reminders"))).isNotNull()
        assertThat(custom.parse(posted())).isNull()
    }

    @Test
    fun summariesAndOngoingNotificationsAreIgnored() {
        assertThat(parser.parse(posted(summary = true))).isNull()
        assertThat(parser.parse(posted(ongoing = true))).isNull()
    }

    @Test
    fun technicalCategoriesAndChannelsAreIgnored() {
        listOf("progress", "service", "status", "sys", "err", "msg", "email", "promo").forEach {
            assertThat(parser.parse(posted(category = it))).isNull()
        }
        listOf("sync_channel", "Backup", "app_update", "foreground_service", "SILENT", "group_summary").forEach {
            assertThat(parser.parse(posted(channelId = it))).isNull()
        }
    }

    @Test
    fun titleFallsBackToBigTitleThenText() {
        assertThat(parser.parse(posted(title = " ", bigTitle = "Big"))?.title).isEqualTo("Big")
        val textOnly = parser.parse(posted(title = null, text = "Call mom"))
        assertThat(textOnly?.title).isEqualTo("Call mom")
        assertThat(textOnly?.notes).isNull()
        assertThat(parser.parse(posted(title = null, text = null, bigText = null))).isNull()
    }

    @Test
    fun bigTextIsPreferredForNotesAndTitleIsTrimmed() {
        val parsed = parser.parse(posted(title = "  Pay bills ", text = "short", bigText = "long text"))

        assertThat(parsed?.title).isEqualTo("Pay bills")
        assertThat(parsed?.notes).isEqualTo("long text")
        assertThat(parser.parse(posted(title = "Same", text = "Same"))?.notes).isNull()
    }

    @Test
    fun deduperIgnoresUpdatesOfTheSameNotification() {
        val deduper = SamsungNotificationDeduper(window = Duration.ofMinutes(2))
        val now = Instant.parse("2026-03-10T08:00:00Z")
        val posted = now.minusSeconds(1)

        assertThat(deduper.tryAcquire("k", posted, now)).isTrue()
        assertThat(deduper.tryAcquire("k", posted, now.plusSeconds(1))).isFalse()
        // Re-posted with a new postTime within the window: still an update.
        assertThat(deduper.tryAcquire("k", now.plusSeconds(30), now.plusSeconds(30))).isFalse()
        // Same postTime is never handled twice, even after the window.
        assertThat(deduper.tryAcquire("k", posted, now.plus(Duration.ofHours(1)))).isFalse()
        // A new postTime after the window is a new reminder ring.
        assertThat(deduper.tryAcquire("k", now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)))).isTrue()
        assertThat(deduper.tryAcquire("other", posted, now)).isTrue()
    }

    @Test
    fun deduperIsBounded() {
        val deduper = SamsungNotificationDeduper(maxEntries = 2)
        val now = Instant.parse("2026-03-10T08:00:00Z")

        deduper.tryAcquire("a", now, now)
        deduper.tryAcquire("b", now, now)
        deduper.tryAcquire("c", now, now)

        // "a" was evicted, so the same notification is accepted again.
        assertThat(deduper.tryAcquire("a", now, now)).isTrue()
        assertThat(deduper.tryAcquire("c", now, now)).isFalse()
    }
}
