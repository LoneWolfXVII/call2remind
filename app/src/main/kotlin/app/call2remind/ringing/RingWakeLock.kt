package app.call2remind.ringing

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Partial wake lock that bridges the alarm broadcast and the ringing service, and keeps the CPU
 * awake while a call rings (so the ring timeout fires on time). The alarm's own wake lock ends
 * with the broadcast, and the device could otherwise sleep before the service reaches
 * `startForeground`. Not reference counted: [acquire] (re)arms one lock with a bounded timeout,
 * [release] drops it. Acquired by the alarm receiver and for each ring by the service, released
 * when the ring ends.
 */
@Singleton
class RingWakeLock @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var lock: PowerManager.WakeLock? = null

    /** Holds the CPU awake for at most [timeoutMs] (or until [release]). */
    @Synchronized
    fun acquire(timeoutMs: Long = RECEIVER_TIMEOUT_MS) {
        val wakeLock = lock ?: context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.also {
                it.setReferenceCounted(false)
                lock = it
            }
            ?: return
        wakeLock.acquire(timeoutMs)
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
    }

    val isHeld: Boolean
        @Synchronized get() = lock?.isHeld == true

    companion object {
        private const val WAKE_LOCK_TAG = "call2remind:ring"

        /** From the alarm receiver to the service's first ring: generous, still bounded. */
        const val RECEIVER_TIMEOUT_MS: Long = 60_000L

        /** Added to the ring timeout to bound one ring's hold; released earlier when the ring ends. */
        const val RING_MARGIN_MS: Long = 60_000L
    }
}
