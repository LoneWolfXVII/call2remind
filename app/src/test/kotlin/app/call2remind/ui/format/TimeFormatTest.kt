package app.call2remind.ui.format

import app.call2remind.settings.OnboardingFlags
import app.call2remind.ui.navigation.BatteryRoute
import app.call2remind.ui.navigation.PermissionsRoute
import app.call2remind.ui.navigation.SelfTestRoute
import app.call2remind.ui.navigation.StartDestination
import app.call2remind.ui.navigation.WelcomeRoute
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class TimeFormatTest {
    private val at = Instant.parse("2026-10-02T09:30:00Z")

    @Test
    fun twelveHourSplitsDigitsAndMeridiem() {
        val parts = TimeFormat.parts(at, ZoneOffset.UTC, Locale.US, is24Hour = false)
        assertThat(parts).isEqualTo(TimeParts("9:30", "AM"))
        assertThat(parts.text).isEqualTo("9:30 AM")
    }

    @Test
    fun twentyFourHourHasNoMeridiem() {
        val parts = TimeFormat.parts(at.plus(Duration.ofHours(12)), ZoneOffset.UTC, Locale.UK, is24Hour = true)
        assertThat(parts).isEqualTo(TimeParts("21:30", null))
    }

    @Test
    fun countdownFormat() {
        assertThat(TimeFormat.minutesSeconds(Duration.ofSeconds(47))).isEqualTo("0:47")
        assertThat(TimeFormat.minutesSeconds(Duration.ofSeconds(125))).isEqualTo("2:05")
        assertThat(TimeFormat.minutesSeconds(Duration.ofSeconds(-3))).isEqualTo("0:00")
    }

    @Test
    fun shortDate() {
        assertThat(TimeFormat.shortDate(at, ZoneOffset.UTC, Locale.UK)).isEqualTo("Fri, 2 Oct")
    }

    @Test
    fun startDestinationResumesOnboarding() {
        assertThat(StartDestination.from(OnboardingFlags())).isEqualTo(StartDestination.Onboarding(WelcomeRoute))
        assertThat(StartDestination.from(OnboardingFlags(welcomeSeen = true))).isEqualTo(StartDestination.Onboarding(PermissionsRoute))
        assertThat(StartDestination.from(OnboardingFlags(welcomeSeen = true, permissionsDone = true)))
            .isEqualTo(StartDestination.Onboarding(BatteryRoute))
        assertThat(StartDestination.from(OnboardingFlags(welcomeSeen = true, permissionsDone = true, batteryOptimizationDone = true)))
            .isEqualTo(StartDestination.Onboarding(SelfTestRoute))
        assertThat(StartDestination.from(OnboardingFlags(selfTestDone = true))).isEqualTo(StartDestination.Home)
    }
}
