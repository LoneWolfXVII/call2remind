package app.call2remind.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import app.call2remind.core.model.SourceType
import app.call2remind.core.ringing.SnoozePolicy
import app.call2remind.core.time.DefaultTimes
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.LocalTime

class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
    }
    private val repo by lazy { DataStoreSettingsRepository(dataStore) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun emptyStoreYieldsDefaults() = runBlocking<Unit> {
        assertThat(repo.current()).isEqualTo(Settings())
        assertThat(repo.current().snoozePolicy).isEqualTo(SnoozePolicy())
    }

    @Test
    fun everyFieldRoundTrips() = runBlocking<Unit> {
        val custom = Settings(
            defaultTimes = DefaultTimes.DEFAULT
                .with(SourceType.GOOGLE_TASKS, LocalTime.of(8, 30))
                .with(SourceType.BIRTHDAY, LocalTime.of(7, 0, 15)),
            snoozeLength = Duration.ofMinutes(15),
            ringTimeout = Duration.ofSeconds(30),
            maxRingBacks = 0,
            defaultRingtoneUri = "content://media/ring/1",
            ttsEnabled = false,
            sourceRingtones = mapOf(SourceType.CALENDAR to "content://media/ring/2"),
            onboarding = OnboardingFlags(
                welcomeSeen = true,
                permissionsDone = true,
                batteryOptimizationDone = false,
                selfTestDone = true,
                dndHintShown = true,
            ),
        )

        repo.update { custom }

        assertThat(repo.current()).isEqualTo(custom)
        assertThat(DataStoreSettingsRepository(dataStore).current()).isEqualTo(custom)
    }

    @Test
    fun clearingNullableValuesRemovesThem() = runBlocking<Unit> {
        repo.update { it.copy(defaultRingtoneUri = "x", sourceRingtones = mapOf(SourceType.HABIT to "y")) }
        repo.update { it.copy(defaultRingtoneUri = null, sourceRingtones = emptyMap()) }

        assertThat(repo.current().defaultRingtoneUri).isNull()
        assertThat(repo.current().sourceRingtones).isEmpty()
    }

    @Test
    fun updateTransformsTheCurrentValue() = runBlocking<Unit> {
        repo.update { it.copy(maxRingBacks = 5) }
        repo.update { it.copy(maxRingBacks = it.maxRingBacks + 1) }
        assertThat(repo.current().maxRingBacks).isEqualTo(6)
    }

    @Test
    fun invalidStoredValuesFallBackToDefaults() = runBlocking<Unit> {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("default_time_CALENDAR")] = "25:99"
            prefs[stringPreferencesKey("default_time_HABIT")] = "06:15"
            prefs[longPreferencesKey("snooze_millis")] = -1
            prefs[longPreferencesKey("ring_timeout_millis")] = 0
            prefs[intPreferencesKey("max_ring_backs")] = -3
        }

        val settings = repo.current()

        assertThat(settings.defaultTimes.calendarAllDay).isEqualTo(DefaultTimes.DEFAULT.calendarAllDay)
        assertThat(settings.defaultTimes.habit).isEqualTo(LocalTime.of(6, 15))
        assertThat(settings.snoozeLength).isEqualTo(Settings.DEFAULT_SNOOZE)
        assertThat(settings.ringTimeout).isEqualTo(Settings.DEFAULT_RING_TIMEOUT)
        assertThat(settings.maxRingBacks).isEqualTo(Settings.DEFAULT_MAX_RING_BACKS)
    }

    @Test
    fun flowEmitsOnlyDistinctValues() = runBlocking<Unit> {
        repo.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            repo.update { it.copy(ttsEnabled = false) }
            assertThat(awaitItem().ttsEnabled).isFalse()
            repo.update { it }
            repo.update { it.copy(ttsEnabled = true) }
            assertThat(awaitItem().ttsEnabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun snoozePolicyIsClampedToValidValues() {
        val broken = Settings(snoozeLength = Duration.ZERO, ringTimeout = Duration.ofSeconds(-5), maxRingBacks = -1)
        assertThat(broken.snoozePolicy).isEqualTo(SnoozePolicy(Settings.DEFAULT_SNOOZE, Settings.DEFAULT_RING_TIMEOUT, 0))

        val custom = Settings(snoozeLength = Duration.ofMinutes(10), ringTimeout = Duration.ofSeconds(20), maxRingBacks = 2)
        assertThat(custom.snoozePolicy).isEqualTo(SnoozePolicy(Duration.ofMinutes(10), Duration.ofSeconds(20), 2))
    }

    @Test
    fun ringtonePrecedenceIsReminderThenSourceThenAppDefault() {
        val settings = Settings(defaultRingtoneUri = "app", sourceRingtones = mapOf(SourceType.CALENDAR to "calendar"))

        assertThat(settings.ringtoneFor(reminder("a", sourceType = SourceType.CALENDAR, ringtoneUri = "own"))).isEqualTo("own")
        assertThat(settings.ringtoneFor(reminder("b", sourceType = SourceType.CALENDAR))).isEqualTo("calendar")
        assertThat(settings.ringtoneFor(reminder("c", sourceType = SourceType.HABIT))).isEqualTo("app")
        assertThat(Settings().ringtoneFor(reminder("d"))).isNull()
    }
}
