package app.call2remind.sources.samsung

import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.SourceType
import app.call2remind.data.model.ReminderIds
import app.call2remind.scheduling.RingStep
import app.call2remind.settings.Settings
import app.call2remind.settings.SourceSettings
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.awaitUntil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class SamsungReminderHandlerTest {
    private val enabled = Settings(sources = SourceSettings().withEnabled(SourceType.SAMSUNG_REMINDER, true))
    private val h = EngineHarness(initialSettings = enabled)
    private val cancelled = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pkg = SourceSettings.SAMSUNG_REMINDER_PACKAGE

    private fun handler(timeout: Duration = Duration.ofSeconds(5)) =
        SamsungReminderHandler(h.engine, h.occurrences, h.settings, h.clock, SamsungNotificationDeduper(), timeout) { UTC }

    private fun posted(key: String = "0|$pkg|7|null|1", postTime: Long = T0.toEpochMilli(), packageName: String = pkg) =
        PostedNotification(key = key, packageName = packageName, postTime = postTime, category = "reminder", title = "Buy milk", text = "2 litres")

    @After
    fun tearDown() {
        scope.cancel()
        h.close()
    }

    @Test
    fun ringsAndCancelsSamsungsNotificationOnceTheRingIsClaimed() = runBlocking<Unit> {
        val handler = handler()
        val result = scope.async { handler.onPosted(posted()) { cancelled += it } }
        val externalId = "0|$pkg|7|null|1@${T0.toEpochMilli()}"
        awaitUntil(message = "occurrence armed") { h.alarms.armed.isNotEmpty() }

        // Nothing is cancelled before our call actually rings.
        assertThat(cancelled).isEmpty()
        val reminder = h.reminders.get(ReminderIds.of(SourceType.SAMSUNG_REMINDER, externalId))
        assertThat(reminder?.title).isEqualTo("Buy milk")
        assertThat(reminder?.notes).isEqualTo("2 litres")
        assertThat(h.alarms.armed.values.single().at).isEqualTo(T0)

        // The alarm receiver claims the ring lock.
        val step = h.engine.claimNext()
        assertThat(step).isInstanceOf(RingStep.Ring::class.java)
        assertThat((step as RingStep.Ring).occurrence.state).isEqualTo(OccurrenceState.RINGING)

        assertThat(result.await()).isEqualTo(SamsungOutcome.RANG)
        assertThat(cancelled).containsExactly("0|$pkg|7|null|1")
    }

    @Test
    fun unclaimedRingKeepsSamsungsNotificationAsFallback() = runBlocking<Unit> {
        val outcome = handler(timeout = Duration.ofMillis(300)).onPosted(posted()) { cancelled += it }

        assertThat(outcome).isEqualTo(SamsungOutcome.PENDING)
        assertThat(cancelled).isEmpty()
        assertThat(h.occurrences.getPending()).hasSize(1)
    }

    @Test
    fun updatesOfTheSameNotificationRingOnce() = runBlocking<Unit> {
        val handler = handler(timeout = Duration.ofMillis(100))

        assertThat(handler.onPosted(posted()) { cancelled += it }).isEqualTo(SamsungOutcome.PENDING)
        assertThat(handler.onPosted(posted()) { cancelled += it }).isEqualTo(SamsungOutcome.DUPLICATE)
        assertThat(h.occurrences.getPending()).hasSize(1)
    }

    @Test
    fun disabledSourceDoesNothing() = runBlocking<Unit> {
        h.settings.update { it.copy(sources = it.sources.withEnabled(SourceType.SAMSUNG_REMINDER, false)) }

        assertThat(handler().onPosted(posted()) { cancelled += it }).isEqualTo(SamsungOutcome.DISABLED)
        assertThat(h.reminders.getAll()).isEmpty()
        assertThat(h.alarms.armCalls).isEmpty()
    }

    @Test
    fun otherPackagesAreIgnoredUnlessConfigured() = runBlocking<Unit> {
        val other = posted(packageName = "com.example.todo")
        assertThat(handler().onPosted(other) { cancelled += it }).isEqualTo(SamsungOutcome.IGNORED)

        h.settings.update { it.copy(sources = it.sources.copy(samsungPackages = setOf("com.example.todo"))) }
        assertThat(handler(timeout = Duration.ofMillis(100)).onPosted(other) { cancelled += it }).isEqualTo(SamsungOutcome.PENDING)
        assertThat(h.reminders.getAll().single().sourceType).isEqualTo(SourceType.SAMSUNG_REMINDER)
    }

    @Test
    fun samsungIsOptInByDefault() {
        assertThat(SourceSettings().isEnabled(SourceType.SAMSUNG_REMINDER)).isFalse()
    }
}
