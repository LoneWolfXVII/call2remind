package app.call2remind.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val _start = MutableStateFlow<StartDestination?>(null)

    /** `null` until the settings are read (a few ms). */
    val start: StateFlow<StartDestination?> = _start.asStateFlow()

    init {
        viewModelScope.launch {
            _start.value = StartDestination.from(settings.current().onboarding)
        }
    }
}
