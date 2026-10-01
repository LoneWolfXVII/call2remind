package app.call2remind.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.settings.SettingsRepository
import app.call2remind.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides the start destination once per launch. Deliberately not a live mapping of the flags:
 * completing a step mid-onboarding must not swap the nav graph under the user.
 */
@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** The theme chosen in Settings (live). */
    val theme: StateFlow<ThemeMode> = settings.settings
        .map { it.themeMode }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    private val _start = MutableStateFlow<StartDestination?>(null)

    /** `null` until the settings are read (a few ms). */
    val start: StateFlow<StartDestination?> = _start.asStateFlow()

    init {
        viewModelScope.launch {
            _start.value = StartDestination.from(settings.current().onboarding)
        }
    }
}
