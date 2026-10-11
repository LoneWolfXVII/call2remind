package app.call2remind.testing

import android.content.ComponentName
import app.call2remind.core.model.SourceType
import app.call2remind.sources.ReminderSource
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.sources.auth.TokenProvider
import app.call2remind.sources.samsung.NotificationAccess
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable [ReminderSource]: [next] produces each snapshot (throw to fail); every request is
 * recorded in [requests].
 */
class FakeReminderSource(
    override val type: SourceType,
    override val requiresNetwork: Boolean = false,
    @Volatile var availability: SourceAvailability = SourceAvailability.Ready,
    @Volatile var next: (SyncRequest) -> SourceSnapshot = { SourceSnapshot.Full(emptyList()) },
) : ReminderSource {
    val requests: MutableList<SyncRequest> = CopyOnWriteArrayList()

    @Volatile
    var disconnected: Int = 0
        private set

    override suspend fun availability(): SourceAvailability = availability

    override suspend fun snapshot(request: SyncRequest): SourceSnapshot {
        requests += request
        return next(request)
    }

    override suspend fun disconnect() {
        disconnected++
    }
}

/** A [TokenProvider] handing out [tokens] in order (the last one repeats); records invalidations. */
class FakeTokenProvider(
    vararg tokens: String,
    @Volatile var connected: Boolean = true,
) : TokenProvider {
    private val queue = ArrayDeque(tokens.toList())
    private var current: String? = null
    val invalidated: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var disconnects: Int = 0
        private set

    override suspend fun isConnected(): Boolean = connected

    private val lock = Any()

    override suspend fun accessToken(): String? = synchronized(lock) {
        if (!connected) return null
        val token = current ?: queue.removeFirstOrNull() ?: return null
        current = token
        token
    }

    override suspend fun invalidate(token: String) = synchronized(lock) {
        invalidated += token
        if (current == token) current = queue.removeFirstOrNull() ?: token
    }

    override suspend fun disconnect() {
        disconnects++
        connected = false
    }
}

class FakeNotificationAccess(@Volatile var granted: Boolean = false) : NotificationAccess {
    override fun isGranted(): Boolean = granted

    override val listenerComponent: ComponentName = ComponentName("app.call2remind", "Listener")
}
