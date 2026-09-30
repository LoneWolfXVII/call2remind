package app.call2remind.testing

import app.call2remind.core.model.Occurrence
import app.call2remind.core.ringing.RingContext
import app.call2remind.ringing.CallStateMonitor
import app.call2remind.ringing.RingAlerts
import app.call2remind.ringing.RingContextProvider
import app.call2remind.ringing.RingLauncher
import app.call2remind.ringing.TtsEvent
import app.call2remind.ringing.TtsPlayer
import app.call2remind.scheduling.AlarmScheduler
import app.call2remind.scheduling.MissedNotifier
import app.call2remind.settings.Settings
import app.call2remind.settings.SettingsRepository
import app.call2remind.work.BackgroundJobs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Records arm/cancel calls; [armed] mirrors what the OS would currently hold. Thread-safe. */
class FakeAlarmScheduler : AlarmScheduler {
    data class Armed(val occurrenceId: String, val at: Instant, val isSoonest: Boolean)

    private val lock = Any()
    private val current = LinkedHashMap<String, Armed>()
    private val armLog = mutableListOf<Armed>()
    private val cancelLog = mutableListOf<String>()

    /** Currently armed alarms by occurrence id. */
    val armed: Map<String, Armed> get() = synchronized(lock) { LinkedHashMap(current) }

    /** Every arm call, in order. */
    val armCalls: List<Armed> get() = synchronized(lock) { armLog.toList() }

    /** Every cancelled occurrence id, in order. */
    val cancelled: List<String> get() = synchronized(lock) { cancelLog.toList() }

    override fun arm(occurrence: Occurrence, triggerAt: Instant, isSoonest: Boolean) {
        synchronized(lock) {
            val entry = Armed(occurrence.id, triggerAt, isSoonest)
            current[occurrence.id] = entry
            armLog += entry
        }
    }

    override fun cancel(occurrence: Occurrence) {
        synchronized(lock) {
            current.remove(occurrence.id)
            cancelLog += occurrence.id
        }
    }

    /** Forgets everything (e.g. a reboot wiped the alarms). */
    fun reset() {
        synchronized(lock) {
            current.clear()
            armLog.clear()
            cancelLog.clear()
        }
    }
}

class FakeRingContextProvider(
    @Volatile var context: RingContext = IDLE,
) : RingContextProvider {
    override fun current(): RingContext = context

    companion object {
        /** Screen on, app in background, no call, no DND, full-screen intents allowed. */
        val IDLE = RingContext(
            inRealCall = false,
            dndTotalSilence = false,
            appInForeground = false,
            canUseFullScreenIntent = true,
            screenInteractive = true,
        )
    }
}

class FakeMissedNotifier : MissedNotifier {
    private val lock = Any()
    private val missedLog = mutableListOf<Occurrence>()
    private val resolvedLog = mutableListOf<Occurrence>()
    private val endedLog = mutableListOf<Occurrence>()

    val missed: List<Occurrence> get() = synchronized(lock) { missedLog.toList() }
    val resolved: List<Occurrence> get() = synchronized(lock) { resolvedLog.toList() }
    val ringEnded: List<Occurrence> get() = synchronized(lock) { endedLog.toList() }

    override suspend fun onMissed(occurrence: Occurrence) {
        synchronized(lock) { missedLog += occurrence }
    }

    override suspend fun onResolved(occurrence: Occurrence) {
        synchronized(lock) { resolvedLog += occurrence }
    }

    override suspend fun onRingEnded(occurrence: Occurrence) {
        synchronized(lock) { endedLog += occurrence }
    }
}

/** In-memory [SettingsRepository]; set [failure] to make reads throw. */
class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    val state = MutableStateFlow(initial)

    @Volatile
    var failure: Exception? = null

    override val settings: Flow<Settings> get() = state

    override suspend fun current(): Settings {
        failure?.let { throw it }
        return state.value
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        state.update(transform)
    }
}

class FakeRingAlerts : RingAlerts {
    data class Start(val ringtoneUri: String?, val sound: Boolean, val vibrate: Boolean)

    private val lock = Any()
    private val startLog = mutableListOf<Start>()

    @Volatile
    var active: Start? = null
        private set

    @Volatile
    var stopCount: Int = 0
        private set

    val starts: List<Start> get() = synchronized(lock) { startLog.toList() }

    override fun start(ringtoneUri: String?, sound: Boolean, vibrate: Boolean) {
        val start = Start(ringtoneUri, sound, vibrate)
        synchronized(lock) { startLog += start }
        active = start
    }

    override fun stop() {
        active = null
        stopCount++
    }
}

class FakeTtsPlayer : TtsPlayer {
    private val lock = Any()
    private val spokenLog = mutableListOf<String>()
    private val flow = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)

    val spoken: List<String> get() = synchronized(lock) { spokenLog.toList() }

    override val events: SharedFlow<TtsEvent> = flow

    override suspend fun speak(text: String): Boolean {
        synchronized(lock) { spokenLog += text }
        return true
    }

    override fun stop() = Unit
}

/** A call state the test flips; [awaitCallEnded] suspends while [inCall] is true. */
class FakeCallStateMonitor : CallStateMonitor {
    val inCall = MutableStateFlow(false)

    override fun isInCall(): Boolean = inCall.value

    override suspend fun awaitCallEnded() {
        inCall.first { !it }
    }
}

class FakeBackgroundJobs : BackgroundJobs {
    @Volatile
    var scheduledCount: Int = 0
        private set

    override fun ensureScheduled() {
        scheduledCount++
    }
}

class FakeRingLauncher : RingLauncher {
    private val lock = Any()
    private val ringLog = mutableListOf<Occurrence>()
    private val deferLog = mutableListOf<List<Occurrence>>()

    val rung: List<Occurrence> get() = synchronized(lock) { ringLog.toList() }
    val deferred: List<List<Occurrence>> get() = synchronized(lock) { deferLog.toList() }

    override suspend fun startRinging(occurrence: Occurrence) {
        synchronized(lock) { ringLog += occurrence }
    }

    override suspend fun deferUntilCallEnds(due: List<Occurrence>) {
        synchronized(lock) { deferLog += due }
    }
}
