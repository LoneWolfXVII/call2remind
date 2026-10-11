package app.call2remind.ui.settings

import app.call2remind.core.model.SourceType
import app.call2remind.settings.Settings
import app.call2remind.settings.ThemeMode
import app.call2remind.testing.FakeSettingsRepository
import app.call2remind.ui.FakeRingtoneCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeSettingsRepository(
        Settings(defaultRingtoneUri = "android.resource://app.call2remind/raw/c2r_switchboard", sourceRingtones = mapOf(SourceType.BIRTHDAY to "content://media/internal/audio/media/7")),
    )
    private lateinit var vm: SettingsViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = SettingsViewModel(repo, FakeRingtoneCatalog(), dispatcher)
        collector = CoroutineScope(dispatcher).launch { vm.state.collect { } }
    }

    @After
    fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun exposesSettingsWithRingtoneTitles() {
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.defaultRingtoneTitle).isEqualTo("Switchboard")
        assertThat(state.sourceRingtoneTitles).containsExactly(SourceType.BIRTHDAY, "Argon")
    }

    @Test
    fun changingADefaultTimeUpdatesOnlyThatSource() {
        vm.setDefaultTime(SourceType.GOOGLE_TASKS, LocalTime.of(7, 30))

        val times = repo.state.value.defaultTimes
        assertThat(times.googleTasks).isEqualTo(LocalTime.of(7, 30))
        assertThat(times.birthday).isEqualTo(LocalTime.of(9, 0))
        assertThat(vm.state.value.settings.defaultTimes.googleTasks).isEqualTo(LocalTime.of(7, 30))
    }

    @Test
    fun ringingAndSnoozeValues() {
        vm.setRingSeconds(60)
        vm.setSnoozeMinutes(15)
        vm.setTts(false)

        with(repo.state.value) {
            assertThat(ringTimeout).isEqualTo(Duration.ofSeconds(60))
            assertThat(snoozeLength).isEqualTo(Duration.ofMinutes(15))
            assertThat(ttsEnabled).isFalse()
        }
    }

    @Test
    fun ringBacksAreClampedToTheOfferedRange() {
        vm.setMaxRingBacks(9)
        assertThat(repo.state.value.maxRingBacks).isEqualTo(SettingsChoices.RING_BACKS.last)

        vm.setMaxRingBacks(-1)
        assertThat(repo.state.value.maxRingBacks).isEqualTo(0)
    }

    @Test
    fun theme() {
        vm.setTheme(ThemeMode.DARK)
        assertThat(repo.state.value.themeMode).isEqualTo(ThemeMode.DARK)
    }
}
