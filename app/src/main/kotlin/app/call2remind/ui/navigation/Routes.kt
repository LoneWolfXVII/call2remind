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

/** The main shell: Up next / Sources / History / Settings tabs. */
@Serializable
data object HomeRoute : Route

/**
 * Reminder detail. [sharedKey] names the element it was opened from (the strip or a timeline
 * row), so the matching title / time / panel fly into the header.
 */
@Serializable
data class ReminderDetailRoute(val occurrenceId: String, val reminderId: String, val sharedKey: String) : Route

/** New habit (`habitId == null`) or edit habit. */
@Serializable
data class HabitEditRoute(val habitId: String? = null) : Route

/** Ringtone picker for a [app.call2remind.ui.ringtone.RingtoneTarget] key; [current] seeds the habit target. */
@Serializable
data class RingtonePickerRoute(val target: String, val current: String? = null) : Route

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
