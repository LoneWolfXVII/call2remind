package app.call2remind.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.call2remind.MainActivity
import app.call2remind.R
import app.call2remind.e2e.support.AppDriver
import app.call2remind.e2e.support.Device
import app.call2remind.e2e.support.E2eRule
import app.call2remind.e2e.support.PKG
import app.call2remind.e2e.support.Waits
import app.call2remind.settings.OnboardingFlags
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh install: the app opens on Welcome, and "Get started" leads to Permissions. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class OnboardingE2eTest {
    @get:Rule
    val e2e = E2eRule()

    private fun string(id: Int): String = Device.context.getString(id)

    @Test
    fun welcomeNavigatesToPermissions() {
        AppDriver.updateSettings { it.copy(onboarding = OnboardingFlags()) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val welcome = Device.ui.wait(Until.findObject(By.pkg(PKG).text(string(R.string.welcome_headline))), 20_000)
            assertWithMessage("Welcome headline").that(welcome).isNotNull()

            val cta = Device.ui.wait(Until.findObject(By.pkg(PKG).text(string(R.string.welcome_cta))), 5_000)
            assertWithMessage("Get started button").that(cta).isNotNull()
            cta!!.click()

            val permissions = Device.ui.wait(Until.findObject(By.pkg(PKG).text(string(R.string.permissions_headline))), 15_000)
            assertWithMessage("Permissions headline after Get started").that(permissions).isNotNull()
            Waits.until("welcome marked seen", 5_000) { AppDriver.settings().onboarding.welcomeSeen }
        }
    }
}
