package app.call2remind.ui.onboarding

import app.call2remind.settings.OnboardingFlags
import app.call2remind.testing.FakeSettingsRepository
import app.call2remind.ui.FakeDeviceSetupChecker
import app.call2remind.ui.system.SetupItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val checker = FakeDeviceSetupChecker(granted = setOf(SetupItem.EXACT_ALARMS))
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rowActionsFollowGrantPaths() {
        val vm = OnboardingViewModel(checker, settings)
        val state = vm.state.value

        assertThat(state.actionFor(SetupItem.NOTIFICATIONS)).isEqualTo(RowAction.REQUEST)
        assertThat(state.actionFor(SetupItem.CALENDAR)).isEqualTo(RowAction.REQUEST)
        assertThat(state.actionFor(SetupItem.FULL_SCREEN)).isEqualTo(RowAction.OPEN_SETTINGS)
        assertThat(state.actionFor(SetupItem.EXACT_ALARMS)).isEqualTo(RowAction.NONE)
        assertThat(state.setup.grantedCount).isEqualTo(1)
    }

    @Test
    fun refreshRereadsTheDevice() {
        val vm = OnboardingViewModel(checker, settings)
        checker.granted = setOf(SetupItem.EXACT_ALARMS, SetupItem.FULL_SCREEN)

        vm.refresh()

        assertThat(vm.state.value.setup.isGranted(SetupItem.FULL_SCREEN)).isTrue()
        assertThat(vm.state.value.actionFor(SetupItem.FULL_SCREEN)).isEqualTo(RowAction.NONE)
    }

    @Test
    fun permanentDenialSendsTheRowToSettings() {
        val vm = OnboardingViewModel(checker, settings)

        vm.onPermissionResult(SetupItem.CONTACTS, granted = false, canAskAgain = true)
        assertThat(vm.state.value.actionFor(SetupItem.CONTACTS)).isEqualTo(RowAction.REQUEST)

        vm.onPermissionResult(SetupItem.CONTACTS, granted = false, canAskAgain = false)
        assertThat(vm.state.value.actionFor(SetupItem.CONTACTS)).isEqualTo(RowAction.OPEN_SETTINGS)
        assertThat(vm.state.value.blocked).containsExactly(SetupItem.CONTACTS)

        checker.granted = checker.granted + SetupItem.CONTACTS
        vm.onPermissionResult(SetupItem.CONTACTS, granted = true, canAskAgain = false)
        assertThat(vm.state.value.actionFor(SetupItem.CONTACTS)).isEqualTo(RowAction.NONE)
        assertThat(vm.state.value.blocked).isEmpty()
    }

    @Test
    fun stepsAreRecordedInOnboardingFlags() {
        val vm = OnboardingViewModel(checker, settings)

        vm.onWelcomeDone()
        vm.onPermissionsDone()
        assertThat(settings.state.value.onboarding).isEqualTo(OnboardingFlags(welcomeSeen = true, permissionsDone = true))

        var finished = false
        vm.onFinished { finished = true }

        assertThat(finished).isTrue()
        with(settings.state.value.onboarding) {
            assertThat(welcomeSeen).isTrue()
            assertThat(permissionsDone).isTrue()
            assertThat(batteryOptimizationDone).isTrue()
            assertThat(selfTestDone).isTrue()
        }
    }
}
