package app.call2remind.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.call2remind.settings.OnboardingFlags
import app.call2remind.settings.SettingsRepository
import app.call2remind.ui.system.DeviceSetupChecker
import app.call2remind.ui.system.DeviceSetupState
import app.call2remind.ui.system.GrantPath
import app.call2remind.ui.system.SetupItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What tapping a permission row should do. */
enum class RowAction {
    /** Show the runtime permission dialog. */
    REQUEST,

    /** Open the relevant system settings page (special access, or a permanently denied permission). */
    OPEN_SETTINGS,

    /** Already granted. */
    NONE,
}

/** Onboarding UI state: the device's setup state plus runtime permissions the user blocked. */
data class OnboardingUiState(
    val setup: DeviceSetupState = DeviceSetupState(),
    /** Runtime permissions denied with "don't ask again": only settings can grant them now. */
    val blocked: Set<SetupItem> = emptySet(),
) {
    fun actionFor(item: SetupItem): RowAction = when {
        setup.isGranted(item) -> RowAction.NONE
        setup.pathOf(item) == GrantPath.RUNTIME && item !in blocked -> RowAction.REQUEST
        else -> RowAction.OPEN_SETTINGS
    }
}

/**
 * Onboarding (Welcome → Permissions → Battery → Self-test). Re-reads the device state on every
 * resume ([refresh]) so rows update after a trip to system settings, and records progress in
 * [OnboardingFlags] so a relaunch resumes at the right step.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val checker: DeviceSetupChecker,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(setup = checker.check()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** The runtime permission behind [item] on this device, if any. */
    fun permissionFor(item: SetupItem): String? = checker.runtimePermission(item)

    fun refresh() {
        _state.update { it.copy(setup = checker.check()) }
    }

    /**
     * Result of a runtime permission request. [canAskAgain] is `shouldShowRequestPermissionRationale`
     * after the denial: false means the system will not show the dialog again.
     */
    fun onPermissionResult(item: SetupItem, granted: Boolean, canAskAgain: Boolean) {
        _state.update {
            it.copy(
                setup = checker.check(),
                blocked = if (!granted && !canAskAgain) it.blocked + item else it.blocked - item,
            )
        }
    }

    fun onWelcomeDone() = updateFlags { it.copy(welcomeSeen = true) }

    fun onPermissionsDone() = updateFlags { it.copy(permissionsDone = true) }

    fun onBatteryDone() = updateFlags { it.copy(batteryOptimizationDone = true) }

    /**
     * Marks onboarding complete (every flag set), whether or not the self-test was run, then calls
     * [then] once it is stored (so the next launch never shows onboarding again).
     */
    fun onFinished(then: () -> Unit = {}) {
        viewModelScope.launch {
            settings.update {
                it.copy(onboarding = it.onboarding.copy(welcomeSeen = true, permissionsDone = true, batteryOptimizationDone = true, selfTestDone = true))
            }
            then()
        }
    }

    private fun updateFlags(transform: (OnboardingFlags) -> OnboardingFlags) {
        viewModelScope.launch { settings.update { it.copy(onboarding = transform(it.onboarding)) } }
    }
}
