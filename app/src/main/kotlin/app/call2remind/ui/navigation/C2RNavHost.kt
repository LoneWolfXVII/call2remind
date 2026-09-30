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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.call2remind.ui.home.HomePlaceholder
import app.call2remind.ui.onboarding.BatteryStep
import app.call2remind.ui.onboarding.OnboardingViewModel
import app.call2remind.ui.onboarding.PermissionsStep
import app.call2remind.ui.onboarding.SelfTestStep
import app.call2remind.ui.onboarding.SelfTestViewModel
import app.call2remind.ui.onboarding.WelcomeScreen
import app.call2remind.ui.theme.C2RMotion
import app.call2remind.ui.theme.C2RTheme

/**
 * The app's navigation: onboarding graph + Home, inside a [SharedTransitionLayout] so Phase 2 can
 * share elements between destinations ([sharedBoundsIfAvailable]).
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
                    HomePlaceholder()
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
