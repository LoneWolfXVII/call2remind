package app.call2remind.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.call2remind.core.model.SourceType
import app.call2remind.core.time.DefaultTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and writes [Settings]. */
interface SettingsRepository {
    /** Current settings, emitting on every change. */
    val settings: Flow<Settings>

    /** Snapshot of the current settings. */
    suspend fun current(): Settings = settings.first()

    /** Atomically replaces the settings with `transform(current)`. */
    suspend fun update(transform: (Settings) -> Settings)
}

/**
 * [SettingsRepository] on Preferences DataStore. Unparseable values fall back to defaults.
 * The DataStore file lives in device-protected storage (see `DataStoreModule`) so ringing
 * works before first unlock.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)
        .distinctUntilChanged()

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(prefs: Preferences): Settings {
        val defaults = Settings()
        val times = SourceType.entries.mapNotNull { type ->
            prefs[defaultTimeKey(type)]?.let(::parseTime)?.let { type to it }
        }.toMap()
        val ringtones = SourceType.entries.mapNotNull { type ->
            prefs[ringtoneKey(type)]?.let { type to it }
        }.toMap()
        return Settings(
            defaultTimes = DefaultTimes.of(times),
            snoozeLength = prefs[SNOOZE_MILLIS]?.takeIf { it > 0 }?.let(Duration::ofMillis) ?: defaults.snoozeLength,
            ringTimeout = prefs[RING_TIMEOUT_MILLIS]?.takeIf { it > 0 }?.let(Duration::ofMillis) ?: defaults.ringTimeout,
            maxRingBacks = prefs[MAX_RING_BACKS]?.takeIf { it >= 0 } ?: defaults.maxRingBacks,
            defaultRingtoneUri = prefs[DEFAULT_RINGTONE],
            ttsEnabled = prefs[TTS_ENABLED] ?: defaults.ttsEnabled,
            sourceRingtones = ringtones,
            onboarding = OnboardingFlags(
                welcomeSeen = prefs[ONB_WELCOME] ?: false,
                permissionsDone = prefs[ONB_PERMISSIONS] ?: false,
                batteryOptimizationDone = prefs[ONB_BATTERY] ?: false,
                selfTestDone = prefs[ONB_SELF_TEST] ?: false,
                dndHintShown = prefs[ONB_DND_HINT] ?: false,
            ),
            sources = readSources(prefs),
        )
    }

    private fun readSources(prefs: Preferences): SourceSettings {
        val defaults = SourceSettings()
        return SourceSettings(
            enabled = prefs[SOURCES_ENABLED]
                ?.mapNotNull { name -> SourceType.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: defaults.enabled,
            calendarDaysAhead = prefs[CALENDAR_DAYS]?.takeIf { it > 0 } ?: defaults.calendarDaysAhead,
            excludedCalendarIds = prefs[CALENDAR_EXCLUDED]?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: defaults.excludedCalendarIds,
            birthdayDayBefore = prefs[BIRTHDAY_DAY_BEFORE] ?: defaults.birthdayDayBefore,
            samsungPackages = prefs[SAMSUNG_PACKAGES] ?: defaults.samsungPackages,
        )
    }

    private fun writeSources(prefs: MutablePreferences, sources: SourceSettings) {
        prefs[SOURCES_ENABLED] = sources.enabled.mapTo(HashSet()) { it.name }
        prefs[CALENDAR_DAYS] = sources.calendarDaysAhead
        prefs[CALENDAR_EXCLUDED] = sources.excludedCalendarIds.mapTo(HashSet()) { it.toString() }
        prefs[BIRTHDAY_DAY_BEFORE] = sources.birthdayDayBefore
        prefs[SAMSUNG_PACKAGES] = sources.samsungPackages
    }

    private fun write(prefs: MutablePreferences, settings: Settings) {
        for (type in SourceType.entries) {
            prefs[defaultTimeKey(type)] = settings.defaultTimes[type].toString()
            val ringtone = settings.sourceRingtones[type]
            if (ringtone == null) prefs.remove(ringtoneKey(type)) else prefs[ringtoneKey(type)] = ringtone
        }
        prefs[SNOOZE_MILLIS] = settings.snoozeLength.toMillis()
        prefs[RING_TIMEOUT_MILLIS] = settings.ringTimeout.toMillis()
        prefs[MAX_RING_BACKS] = settings.maxRingBacks
        val ringtone = settings.defaultRingtoneUri
        if (ringtone == null) prefs.remove(DEFAULT_RINGTONE) else prefs[DEFAULT_RINGTONE] = ringtone
        prefs[TTS_ENABLED] = settings.ttsEnabled
        prefs[ONB_WELCOME] = settings.onboarding.welcomeSeen
        prefs[ONB_PERMISSIONS] = settings.onboarding.permissionsDone
        prefs[ONB_BATTERY] = settings.onboarding.batteryOptimizationDone
        prefs[ONB_SELF_TEST] = settings.onboarding.selfTestDone
        prefs[ONB_DND_HINT] = settings.onboarding.dndHintShown
        writeSources(prefs, settings.sources)
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text)
    } catch (e: DateTimeException) {
        null
    }

    private companion object {
        val SNOOZE_MILLIS = longPreferencesKey("snooze_millis")
        val RING_TIMEOUT_MILLIS = longPreferencesKey("ring_timeout_millis")
        val MAX_RING_BACKS = intPreferencesKey("max_ring_backs")
        val DEFAULT_RINGTONE = stringPreferencesKey("default_ringtone_uri")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val ONB_WELCOME = booleanPreferencesKey("onboarding_welcome_seen")
        val ONB_PERMISSIONS = booleanPreferencesKey("onboarding_permissions_done")
        val ONB_BATTERY = booleanPreferencesKey("onboarding_battery_done")
        val ONB_SELF_TEST = booleanPreferencesKey("onboarding_self_test_done")
        val ONB_DND_HINT = booleanPreferencesKey("onboarding_dnd_hint_shown")
        val SOURCES_ENABLED = stringSetPreferencesKey("sources_enabled")
        val CALENDAR_DAYS = intPreferencesKey("calendar_days_ahead")
        val CALENDAR_EXCLUDED = stringSetPreferencesKey("calendar_excluded_ids")
        val BIRTHDAY_DAY_BEFORE = booleanPreferencesKey("birthday_day_before")
        val SAMSUNG_PACKAGES = stringSetPreferencesKey("samsung_packages")

        fun defaultTimeKey(type: SourceType) = stringPreferencesKey("default_time_${type.name}")
        fun ringtoneKey(type: SourceType) = stringPreferencesKey("ringtone_${type.name}")
    }
}
