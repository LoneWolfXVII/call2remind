package app.call2remind.sources.samsung

import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.sources.SourceIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What [SamsungReminderHandler.onPosted] did. */
enum class SamsungOutcome {
    /** The Samsung source is turned off in settings. */
    DISABLED,

    /** Not a Samsung reminder (other package, summary, ongoing, no text…). */
    IGNORED,

    /** An update of a notification we already handled. */
    DUPLICATE,

    /** Our call rang (lock claimed) and Samsung's own notification was cancelled. */
    RANG,

    /** The call was scheduled but not claimed within the timeout (e.g. user on a phone call); Samsung's notification is kept as a fallback. */
    PENDING,
}

/**
 * Turns a Samsung Reminders notification into a call: parse → dedupe →
 * [SchedulingEngine.ringImmediately] (stores the reminder + occurrence and arms an immediate
 * alarm; the alarm receiver takes the exactly-once ring lock) → wait until the occurrence leaves
 * SCHEDULED, i.e. our ring was claimed → only then cancel Samsung's notification via [cancel].
 * If the ring is not claimed within [claimTimeout], Samsung's notification stays as a fallback.
 */
@Singleton
class SamsungReminderHandler(
    private val engine: SchedulingEngine,
    private val occurrences: OccurrenceRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val deduper: SamsungNotificationDeduper,
    private val claimTimeout: Duration,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        engine: SchedulingEngine,
        occurrences: OccurrenceRepository,
        settings: SettingsRepository,
        clock: Clock,
    ) : this(engine, occurrences, settings, clock, SamsungNotificationDeduper(), DEFAULT_CLAIM_TIMEOUT, { clock.zone })

    suspend fun onPosted(notification: PostedNotification, cancel: (key: String) -> Unit): SamsungOutcome {
        val sourceSettings = settings.current().sources
        if (!sourceSettings.isEnabled(SourceType.SAMSUNG_REMINDER)) return SamsungOutcome.DISABLED
        val parsed = SamsungNotificationParser(sourceSettings.samsungPackages).parse(notification)
            ?: return SamsungOutcome.IGNORED
        if (!deduper.tryAcquire(parsed.key, parsed.postedAt, clock.instant())) return SamsungOutcome.DUPLICATE

        val occurrence = engine.ringImmediately(parsed.toReminder(zone()), parsed.postedAt, SourceIds.SAMSUNG_REMINDERS)
        val settled = withTimeoutOrNull(claimTimeout.toMillis()) {
            occurrences.observe(occurrence.id).first { it == null || it.state != OccurrenceState.SCHEDULED }
        }
        return if (settled != null) {
            cancel(parsed.key)
            SamsungOutcome.RANG
        } else {
            SamsungOutcome.PENDING
        }
    }

    companion object {
        val DEFAULT_CLAIM_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
