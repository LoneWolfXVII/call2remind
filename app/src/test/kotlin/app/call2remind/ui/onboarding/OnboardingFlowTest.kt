package app.call2remind.ui.onboarding

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.call2remind.R
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeSettingsRepository
import app.call2remind.testing.awaitUntil
import app.call2remind.ui.FakeDeviceSetupChecker
import app.call2remind.ui.navigation.BatteryRoute
import app.call2remind.ui.navigation.C2RNavHost
import app.call2remind.ui.navigation.StartDestination
import app.call2remind.ui.navigation.WelcomeRoute
import app.call2remind.ui.system.DeviceSetupState
import app.call2remind.ui.system.GrantPath
import app.call2remind.ui.system.SetupItem
import app.call2remind.ui.theme.Call2RemindTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val h = EngineHarness()
    private val settings = FakeSettingsRepository()
    private val checker = FakeDeviceSetupChecker(granted = setOf(SetupItem.NOTIFICATIONS, SetupItem.EXACT_ALARMS))

    @After
    fun tearDown() = h.close()

    private fun text(id: Int) = context.getString(id)

    private companion object {
        const val HOME = "Home shell"
    }

    @Test
    fun walksThroughEveryStepToHome() {
        val onboarding = OnboardingViewModel(checker, settings)
        val selfTest = SelfTestViewModel(h.engine, h.occurrences, h.clock)
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = false) {
                C2RNavHost(
                    start = StartDestination.Onboarding(WelcomeRoute),
                    onboarding = onboarding,
                    selfTest = selfTest,
                    homeContent = { Text(HOME) },
                )
            }
        }

        rule.onNodeWithText(text(R.string.welcome_headline)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.welcome_cta)).performClick()

        rule.onNodeWithText(text(R.string.permissions_headline)).assertIsDisplayed()
        assertThat(settings.state.value.onboarding.welcomeSeen).isTrue()
        rule.onNodeWithText(text(R.string.action_continue)).performClick()

        rule.onNodeWithText(text(R.string.battery_headline)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.battery_not_now)).performClick()

        rule.onNodeWithText(text(R.string.selftest_idle_headline)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.selftest_skip)).performClick()
        awaitUntil(message = "onboarding stored") { settings.state.value.onboarding.selfTestDone }
        rule.waitForIdle()

        rule.onNodeWithText(HOME).assertIsDisplayed()
        with(settings.state.value.onboarding) {
            assertThat(permissionsDone).isTrue()
            assertThat(batteryOptimizationDone).isTrue()
        }
    }

    @Test
    fun samsungGetsTheDeviceCareWalkthrough() {
        checker.isSamsung = true
        val onboarding = OnboardingViewModel(checker, settings)
        val selfTest = SelfTestViewModel(h.engine, h.occurrences, h.clock)
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                C2RNavHost(
                    start = StartDestination.Onboarding(BatteryRoute),
                    onboarding = onboarding,
                    selfTest = selfTest,
                )
            }
        }

        rule.onNodeWithText(text(R.string.battery_samsung_headline)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.battery_step2)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(text(R.string.battery_done)).performClick()
        rule.onNodeWithText(text(R.string.selftest_idle_headline)).assertIsDisplayed()
    }

    @Test
    fun permissionRowsShowLiveState() {
        val state = mutableStateOf(
            OnboardingUiState(
                setup = DeviceSetupState(
                    granted = setOf(SetupItem.NOTIFICATIONS),
                    paths = SetupItem.entries.associateWith { GrantPath.RUNTIME },
                ),
            ),
        )
        val allowed = mutableListOf<SetupItem>()
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                PermissionsScreen(state = state.value, onAllow = { allowed += it }, onContinue = {}, onBack = null)
            }
        }

        rule.onNodeWithTag(OnboardingTags.rowGranted(SetupItem.NOTIFICATIONS.name), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTags.rowAction(SetupItem.NOTIFICATIONS.name)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.permissions_summary, 1, SetupItem.entries.size)).assertIsDisplayed()

        rule.onNodeWithTag(OnboardingTags.rowAction(SetupItem.CALENDAR.name)).performScrollTo().performClick()
        assertThat(allowed).containsExactly(SetupItem.CALENDAR)

        // Granted on return from the dialog / settings: the row flips to "Allowed".
        state.value = state.value.copy(setup = state.value.setup.copy(granted = setOf(SetupItem.NOTIFICATIONS, SetupItem.CALENDAR)))
        rule.waitForIdle()
        rule.onNodeWithTag(OnboardingTags.rowGranted(SetupItem.CALENDAR.name), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(OnboardingTags.rowAction(SetupItem.CALENDAR.name)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.permissions_summary, 2, SetupItem.entries.size)).assertIsDisplayed()
    }

    @Test
    fun rowsRecheckWhenTheScreenResumes() {
        val onboarding = OnboardingViewModel(checker, settings)
        val before = checker.checks
        rule.setContent {
            Call2RemindTheme(darkTheme = false, reduceMotion = true) {
                PermissionsStep(viewModel = onboarding, onContinue = {}, onBack = null)
            }
        }
        rule.waitForIdle()

        assertThat(checker.checks).isGreaterThan(before)
    }
}
