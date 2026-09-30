package app.call2remind.settings

import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.SnoozePolicy
import app.call2remind.core.time.DefaultTimes
import java.time.Duration

/**
 * User settings. Immutable; change via [SettingsRepository.update].
 *
 * @property defaultTimes ring time for date-only items, per source.
 * @property snoozeLength Decline / ring-timeout snooze length.
 * @property ringTimeout how long a call rings unanswered.
 * @property maxRingBacks ring-backs before an occurrence is marked missed.
 * @property defaultRingtoneUri app-wide ringtone; `null` = system default alarm sound.
 * @property sourceRingtones per-source ringtone overrides.
 * @property sources per-source sync options (enable flags, calendar window, birthdays…).
 */
data class Settings(
    val defaultTimes: DefaultTimes = DefaultTimes.DEFAULT,
    val snoozeLength: Duration = DEFAULT_SNOOZE,
    val ringTimeout: Duration = DEFAULT_RING_TIMEOUT,
    val maxRingBacks: Int = DEFAULT_MAX_RING_BACKS,
    val defaultRingtoneUri: String? = null,
    val ttsEnabled: Boolean = true,
    val sourceRingtones: Map<SourceType, String> = emptyMap(),
    val onboarding: OnboardingFlags = OnboardingFlags(),
    val sources: SourceSettings = SourceSettings(),
) {
    /** The snooze policy for the state machine / recovery (values are clamped to be valid). */
    val snoozePolicy: SnoozePolicy
        get() = SnoozePolicy(
            defaultSnooze = snoozeLength.takeIf { it > Duration.ZERO } ?: DEFAULT_SNOOZE,
            ringTimeout = ringTimeout.takeIf { it > Duration.ZERO } ?: DEFAULT_RING_TIMEOUT,
            maxRingBacks = maxRingBacks.coerceAtLeast(0),
        )

    /** Ringtone for [reminder]: reminder override, else source override, else app default. */
    fun ringtoneFor(reminder: Reminder): String? =
        reminder.ringtoneUri ?: sourceRingtones[reminder.sourceType] ?: defaultRingtoneUri

    companion object {
        val DEFAULT_SNOOZE: Duration = Duration.ofMinutes(5)
        val DEFAULT_RING_TIMEOUT: Duration = Duration.ofSeconds(45)
        const val DEFAULT_MAX_RING_BACKS: Int = 3
    }
}

/** One-time onboarding / hint flags. */
data class OnboardingFlags(
    val welcomeSeen: Boolean = false,
    val permissionsDone: Boolean = false,
    val batteryOptimizationDone: Boolean = false,
    val selfTestDone: Boolean = false,
    /** The "calls are silent in Do Not Disturb" hint was shown. */
    val dndHintShown: Boolean = false,
)
