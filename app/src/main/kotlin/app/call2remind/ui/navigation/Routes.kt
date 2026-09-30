package app.call2remind.ui.navigation

import app.call2remind.settings.OnboardingFlags
import kotlinx.serialization.Serializable

/** Type-safe routes (navigation-compose + kotlinx.serialization). */
sealed interface Route

@Serializable
data object OnboardingGraph : Route

@Serializable
data object WelcomeRoute : Route

@Serializable
data object PermissionsRoute : Route

@Serializable
data object BatteryRoute : Route

@Serializable
data object SelfTestRoute : Route

/** Phase 2 replaces the placeholder behind this with Home / Up next. */
@Serializable
data object HomeRoute : Route

/** Where the app opens, decided once per launch from the stored onboarding flags. */
sealed interface StartDestination {
    data object Home : StartDestination

    /** Onboarding, resumed at [step]. */
    data class Onboarding(val step: Route) : StartDestination

    companion object {
        fun from(flags: OnboardingFlags): StartDestination = when {
            flags.selfTestDone -> Home
            flags.batteryOptimizationDone -> Onboarding(SelfTestRoute)
            flags.permissionsDone -> Onboarding(BatteryRoute)
            flags.welcomeSeen -> Onboarding(PermissionsRoute)
            else -> Onboarding(WelcomeRoute)
        }
    }
}
