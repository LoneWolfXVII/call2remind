@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.call2remind.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.call2remind.ui.detail.DetailRoute
import app.call2remind.ui.habit.HabitEditorRoute
import app.call2remind.ui.home.HomeNav
import app.call2remind.ui.home.HomeShell
import app.call2remind.ui.ringtone.RingtoneRoute
import app.call2remind.ui.ringtone.RingtoneTarget
import app.call2remind.ui.settings.SettingsNav
import app.call2remind.ui.onboarding.BatteryStep
import app.call2remind.ui.onboarding.OnboardingViewModel
import app.call2remind.ui.onboarding.PermissionsStep
import app.call2remind.ui.onboarding.SelfTestStep
import app.call2remind.ui.onboarding.SelfTestViewModel
import app.call2remind.ui.onboarding.WelcomeScreen
import app.call2remind.ui.theme.C2RMotion
import app.call2remind.ui.theme.C2RTheme

/**
 * The app's navigation: onboarding graph, the Home shell and its detail destinations (reminder
 * detail, habit editor, ringtone picker), inside a [SharedTransitionLayout] so Home → Detail shares
 * the title, time and caller-ID panel ([sharedBoundsIfAvailable], [sharedTextIfAvailable]).
 *
 * Transitions are springs (precise: position must not overshoot a whole screen) and are seekable,
 * so with `enableOnBackInvokedCallback` the predictive back gesture scrubs the pop transition.
 */
@Composable
fun C2RNavHost(
    start: StartDestination,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    // Activity-scoped (resolved outside any destination), so they survive the onboarding steps.
    onboarding: OnboardingViewModel = hiltViewModel(),
    selfTest: SelfTestViewModel = hiltViewModel(),
    // The Home shell; tests of the onboarding flow swap in a stand-in.
    homeContent: @Composable (HomeNav) -> Unit = { HomeShell(it) },
) {
    val motion = C2RTheme.motion
    val startRoute: Any = when (start) {
        StartDestination.Home -> HomeRoute
        is StartDestination.Onboarding -> OnboardingGraph
    }
    val onboardingStart: Route = (start as? StartDestination.Onboarding)?.step ?: WelcomeRoute

    SharedTransitionLayout(modifier.fillMaxSize().background(C2RTheme.colors.ground)) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = startRoute,
                enterTransition = { forward(motion) },
                exitTransition = { forwardExit(motion) },
                popEnterTransition = { back(motion) },
                popExitTransition = { backExit(motion) },
            ) {
                navigation<OnboardingGraph>(startDestination = onboardingStart) {
                    destination<WelcomeRoute> {
                        WelcomeScreen(
                            onGetStarted = dropUnlessResumed {
                                onboarding.onWelcomeDone()
                                navController.navigate(PermissionsRoute)
                            },
                        )
                    }
                    destination<PermissionsRoute> {
                        PermissionsStep(
                            viewModel = onboarding,
                            onContinue = dropUnlessResumed { navController.navigate(BatteryRoute) },
                            onBack = navController.backOrNull(),
                        )
                    }
                    destination<BatteryRoute> {
                        BatteryStep(
                            viewModel = onboarding,
                            onContinue = dropUnlessResumed { navController.navigate(SelfTestRoute) },
                            onBack = navController.backOrNull(),
                        )
                    }
                    destination<SelfTestRoute> {
                        SelfTestStep(
                            viewModel = selfTest,
                            onFinish = {
                                onboarding.onFinished {
                                    navController.navigate(HomeRoute) {
                                        popUpTo<OnboardingGraph> { inclusive = true }
                                        // Opened again from Settings: land on the existing Home.
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onReviewPermissions = {
                                if (!navController.popBackStack<PermissionsRoute>(inclusive = false)) {
                                    navController.navigate(PermissionsRoute)
                                }
                            },
                            onBack = navController.backOrNull(),
                        )
                    }
                }
                destination<HomeRoute> {
                    homeContent(
                        HomeNav(
                            openDetail = { occurrenceId, reminderId, sharedKey ->
                                navController.navigateOnce(ReminderDetailRoute(occurrenceId, reminderId, sharedKey))
                            },
                            newHabit = { navController.navigateOnce(HabitEditRoute()) },
                            editHabit = { id -> navController.navigateOnce(HabitEditRoute(id)) },
                            settings = SettingsNav(
                                openRingtone = { target -> navController.navigateOnce(RingtonePickerRoute(target.key)) },
                                openPermissions = { navController.navigateOnce(PermissionsRoute) },
                                openTestCall = { navController.navigateOnce(SelfTestRoute) },
                            ),
                        ),
                    )
                }
                destination<ReminderDetailRoute> { entry ->
                    val route = entry.toRoute<ReminderDetailRoute>()
                    DetailRoute(
                        sharedKey = route.sharedKey,
                        onBack = dropUnlessResumed { navController.navigateUp() },
                        onEditHabit = { id -> navController.navigateOnce(HabitEditRoute(id)) },
                    )
                }
                destination<HabitEditRoute> { entry ->
                    val result by entry.savedStateHandle.getStateFlow<String?>(RINGTONE_RESULT, null).collectAsStateWithLifecycle()
                    HabitEditorRoute(
                        ringtoneResult = result,
                        onRingtoneResultConsumed = { entry.savedStateHandle[RINGTONE_RESULT] = null },
                        onPickRingtone = { current ->
                            navController.navigateOnce(RingtonePickerRoute(RingtoneTarget.Habit.key, current))
                        },
                        onDone = { deleted ->
                            // A deleted habit's Detail is gone too: back to Home.
                            if (!deleted || !navController.popBackStack<HomeRoute>(inclusive = false)) navController.navigateUp()
                        },
                    )
                }
                destination<RingtonePickerRoute> {
                    RingtoneRoute(
                        onBack = dropUnlessResumed { navController.navigateUp() },
                        onHabitResult = { uri ->
                            navController.previousBackStackEntry?.savedStateHandle?.set(RINGTONE_RESULT, uri.orEmpty())
                        },
                    )
                }
            }
        }
    }
}

/** A composable destination that provides its [LocalNavAnimatedVisibilityScope]. */
private inline fun <reified T : Any> NavGraphBuilder.destination(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            content(entry)
        }
    }
}

/** Result key: the ringtone picked for a habit ("" = app default). */
private const val RINGTONE_RESULT = "ringtone_result"

/** Navigates unless the current destination is still leaving (double taps during a transition). */
private fun NavHostController.navigateOnce(route: Any) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) navigate(route)
}

/** Back arrow action when there is somewhere to go back to in this graph. */
private fun NavHostController.backOrNull(): (() -> Unit)? =
    if (previousBackStackEntry != null) ({ navigateUp() }) else null

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward(motion: C2RMotion): EnterTransition =
    if (motion.reduced) {
        motion.fadeInOnly()
    } else {
        slideIntoContainer(SlideDirection.Start, motion.slide()) { it / 4 } + fadeIn(motion.fade())
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExit(motion: C2RMotion): ExitTransition =
    if (motion.reduced) {
        motion.fadeOutOnly()
    } else {
        slideOutOfContainer(SlideDirection.Start, motion.slide()) { it / 8 } + fadeOut(motion.fade())
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.back(motion: C2RMotion): EnterTransition =
    if (motion.reduced) {
        motion.fadeInOnly()
    } else {
        slideIntoContainer(SlideDirection.End, motion.slide()) { it / 8 } + fadeIn(motion.fade())
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExit(motion: C2RMotion): ExitTransition =
    if (motion.reduced) {
        motion.fadeOutOnly()
    } else {
        slideOutOfContainer(SlideDirection.End, motion.slide()) { it / 4 } + fadeOut(motion.fade())
    }
