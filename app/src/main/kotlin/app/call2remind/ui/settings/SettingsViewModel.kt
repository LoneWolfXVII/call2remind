package app.call2remind.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.core.model.SourceType
import app.call2remind.di.IoDispatcher
import app.call2remind.settings.Settings
import app.call2remind.settings.SettingsRepository
import app.call2remind.settings.ThemeMode
import app.call2remind.ui.ringtone.RingtoneCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: Settings = Settings(),
    /** Titles of the stored ringtone uris (default + per source); `null` = the default alarm sound. */
    val defaultRingtoneTitle: String? = null,
    val sourceRingtoneTitles: Map<SourceType, String?> = emptyMap(),
)

/** The choices Settings offers (also used to validate updates). */
object SettingsChoices {
    val RING_SECONDS = listOf(30L, 45L, 60L)
    val SNOOZE_MINUTES = listOf(5L, 10L, 15L, 30L)
    val RING_BACKS = 0..5

    /** Sources whose date-only items ring at a default time, in display order. */
    val DATE_ONLY_SOURCES = listOf(SourceType.CALENDAR, SourceType.GOOGLE_TASKS, SourceType.SAMSUNG_REMINDER, SourceType.BIRTHDAY)

    /** Sources that can have their own ringtone. */
    val RINGTONE_SOURCES = listOf(
        SourceType.CALENDAR,
        SourceType.GOOGLE_TASKS,
        SourceType.MS_TODO,
        SourceType.SAMSUNG_REMINDER,
        SourceType.BIRTHDAY,
        SourceType.HABIT,
    )
}

/** Settings: every value is read from and written to [SettingsRepository] (DataStore). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val catalog: RingtoneCatalog,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { s ->
            SettingsUiState(
                loading = false,
                settings = s,
                defaultRingtoneTitle = catalog.titleFor(s.defaultRingtoneUri),
                sourceRingtoneTitles = s.sourceRingtones.mapValues { (_, uri) -> catalog.titleFor(uri) },
            )
        }
        .distinctUntilChanged()
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setRingSeconds(seconds: Long) = update { it.copy(ringTimeout = Duration.ofSeconds(seconds.coerceIn(10, 180))) }

    fun setSnoozeMinutes(minutes: Long) = update { it.copy(snoozeLength = Duration.ofMinutes(minutes.coerceIn(1, 240))) }

    fun setMaxRingBacks(count: Int) = update { it.copy(maxRingBacks = count.coerceIn(SettingsChoices.RING_BACKS)) }

    fun setTts(enabled: Boolean) = update { it.copy(ttsEnabled = enabled) }

    fun setDefaultTime(type: SourceType, time: LocalTime) = update { it.copy(defaultTimes = it.defaultTimes.with(type, time)) }

    fun setTheme(mode: ThemeMode) = update { it.copy(themeMode = mode) }
}
