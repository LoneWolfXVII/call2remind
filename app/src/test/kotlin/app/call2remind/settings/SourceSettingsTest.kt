package app.call2remind.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.call2remind.core.model.SourceType
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

class SourceSettingsTest {
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
    fun defaults() {
        val defaults = SourceSettings()

        assertThat(defaults.enabled).containsExactly(
            SourceType.CALENDAR, SourceType.BIRTHDAY, SourceType.HABIT, SourceType.GOOGLE_TASKS, SourceType.MS_TODO,
        )
        assertThat(defaults.isEnabled(SourceType.SAMSUNG_REMINDER)).isFalse()
        assertThat(defaults.calendarDaysAhead).isEqualTo(7)
        assertThat(defaults.birthdayDayBefore).isFalse()
        assertThat(defaults.samsungPackages).containsExactly("com.samsung.android.app.reminder")
    }

    @Test
    fun calendarWindowIsClamped() {
        assertThat(SourceSettings(calendarDaysAhead = 0).calendarWindowDays).isEqualTo(1)
        assertThat(SourceSettings(calendarDaysAhead = 365).calendarWindowDays).isEqualTo(SourceSettings.MAX_CALENDAR_DAYS)
        assertThat(SourceSettings(calendarDaysAhead = 14).calendarWindowDays).isEqualTo(14)
    }

    @Test
    fun withEnabledTogglesOneType() {
        val off = SourceSettings().withEnabled(SourceType.CALENDAR, false)

        assertThat(off.isEnabled(SourceType.CALENDAR)).isFalse()
        assertThat(off.withEnabled(SourceType.CALENDAR, true)).isEqualTo(SourceSettings())
    }

    @Test
    fun emptyStoreYieldsDefaultSourceSettings() = runBlocking<Unit> {
        assertThat(repo.current().sources).isEqualTo(SourceSettings())
    }

    @Test
    fun sourceSettingsRoundTrip() = runBlocking<Unit> {
        val custom = SourceSettings(
            enabled = setOf(SourceType.SAMSUNG_REMINDER, SourceType.CALENDAR),
            calendarDaysAhead = 14,
            excludedCalendarIds = setOf(3L, 42L),
            birthdayDayBefore = true,
            samsungPackages = setOf("com.samsung.android.app.reminder", "com.example.todo"),
        )

        repo.update { it.copy(sources = custom) }

        assertThat(repo.current().sources).isEqualTo(custom)
        assertThat(DataStoreSettingsRepository(dataStore).current().sources).isEqualTo(custom)
    }

    @Test
    fun everythingDisabledIsKeptAsEmpty() = runBlocking<Unit> {
        repo.update { it.copy(sources = it.sources.copy(enabled = emptySet())) }

        assertThat(repo.current().sources.enabled).isEmpty()
    }

    @Test
    fun unknownOrInvalidStoredValuesFallBack() = runBlocking<Unit> {
        dataStore.edit {
            it[stringSetPreferencesKey("sources_enabled")] = setOf("CALENDAR", "FAX")
            it[intPreferencesKey("calendar_days_ahead")] = -3
            it[stringSetPreferencesKey("calendar_excluded_ids")] = setOf("7", "x")
        }

        val sources = repo.current().sources

        assertThat(sources.enabled).containsExactly(SourceType.CALENDAR)
        assertThat(sources.calendarDaysAhead).isEqualTo(SourceSettings.DEFAULT_CALENDAR_DAYS)
        assertThat(sources.excludedCalendarIds).containsExactly(7L)
    }
}
