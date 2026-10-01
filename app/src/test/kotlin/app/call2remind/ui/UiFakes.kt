package app.call2remind.ui

import app.call2remind.ringing.TtsEvent
import app.call2remind.ringing.TtsPlayer
import app.call2remind.ui.system.DeviceSetupChecker
import app.call2remind.ui.system.DeviceSetupState
import app.call2remind.ui.system.GrantPath
import app.call2remind.ui.system.SetupItem
import app.call2remind.ui.ringtone.DEFAULT_TONE_KEY
import app.call2remind.ui.ringtone.RingtoneCatalog
import app.call2remind.ui.ringtone.Tone
import app.call2remind.ui.ringtone.TonePreviewPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

/** A [DeviceSetupChecker] whose answers the test sets. */
class FakeDeviceSetupChecker(
    var granted: Set<SetupItem> = emptySet(),
    var batteryUnrestricted: Boolean = false,
    var isSamsung: Boolean = false,
    var paths: Map<SetupItem, GrantPath> = mapOf(
        SetupItem.NOTIFICATIONS to GrantPath.RUNTIME,
        SetupItem.FULL_SCREEN to GrantPath.SETTINGS,
        SetupItem.EXACT_ALARMS to GrantPath.AUTOMATIC,
        SetupItem.CALENDAR to GrantPath.RUNTIME,
        SetupItem.CONTACTS to GrantPath.RUNTIME,
    ),
) : DeviceSetupChecker {
    var checks: Int = 0
        private set

    override fun check(): DeviceSetupState {
        checks++
        return DeviceSetupState(granted, paths, batteryUnrestricted, isSamsung)
    }

    override fun runtimePermission(item: SetupItem): String? = when (item) {
        SetupItem.NOTIFICATIONS -> "android.permission.POST_NOTIFICATIONS"
        SetupItem.CALENDAR -> "android.permission.READ_CALENDAR"
        SetupItem.CONTACTS -> "android.permission.READ_CONTACTS"
        else -> null
    }
}

/** A [TtsPlayer] that records calls and lets the test emit progress events. */
class ScriptedTtsPlayer : TtsPlayer {
    private val flow = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TtsEvent> = flow

    val spoken = mutableListOf<String>()
    var stops = 0
        private set

    fun emit(event: TtsEvent) {
        check(flow.tryEmit(event))
    }

    override suspend fun speak(text: String): Boolean {
        spoken += text
        return true
    }

    override fun stop() {
        stops++
    }
}

/** A [RingtoneCatalog] with fixed tones; titles come from the bundled list or [titles]. */
class FakeRingtoneCatalog(
    private val bundled: List<Tone> = listOf(
        Tone("android.resource://app.call2remind/raw/c2r_switchboard", "Switchboard", "Two short bursts, repeating"),
        Tone("android.resource://app.call2remind/raw/c2r_desk_bell", "Desk bell", "Bright double bell"),
    ),
    private val system: List<Tone> = listOf(Tone("content://media/internal/audio/media/7", "Argon")),
    private val titles: Map<String, String> = emptyMap(),
) : RingtoneCatalog {
    override fun bundled(): List<Tone> = bundled

    override fun system(): List<Tone> = system

    override fun titleFor(uri: String?): String? =
        uri?.let { u -> (bundled + system).firstOrNull { it.uri == u }?.title ?: titles[u] }
}

/** Records previews instead of playing them. */
class FakeTonePreviewPlayer : TonePreviewPlayer {
    private val _playing = MutableStateFlow<String?>(null)
    override val playing: StateFlow<String?> = _playing
    val played = mutableListOf<String?>()

    override fun play(uri: String?) {
        played += uri
        _playing.value = uri ?: DEFAULT_TONE_KEY
    }

    override fun stop() {
        _playing.value = null
    }
}
