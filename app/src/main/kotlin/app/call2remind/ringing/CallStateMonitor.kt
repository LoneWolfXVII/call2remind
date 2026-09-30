package app.call2remind.ringing

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Watches whether the user is in a real phone/VoIP call (no READ_PHONE_STATE needed). */
interface CallStateMonitor {
    fun isInCall(): Boolean

    /** Suspends until no call is active (returns immediately if none is). */
    suspend fun awaitCallEnded()
}

/**
 * [CallStateMonitor] on [AudioManager.getMode]. API 31+ listens with
 * `addOnModeChangedListener` (re-checking every [RECHECK_MS] as a safety net); older APIs poll.
 */
@Singleton
class AndroidCallStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallStateMonitor {
    private val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))

    override fun isInCall(): Boolean = AndroidRingContextProvider.isInCallMode(audioManager.mode)

    override suspend fun awaitCallEnded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val changes = modeChanges()
            while (isInCall()) withTimeoutOrNull(RECHECK_MS) { changes.first() }
        } else {
            while (isInCall()) delay(POLL_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun modeChanges(): Flow<Int> = callbackFlow {
        val listener = AudioManager.OnModeChangedListener { mode -> trySend(mode) }
        audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
        awaitClose { audioManager.removeOnModeChangedListener(listener) }
    }

    private companion object {
        const val RECHECK_MS = 30_000L
        const val POLL_MS = 5_000L
    }
}
