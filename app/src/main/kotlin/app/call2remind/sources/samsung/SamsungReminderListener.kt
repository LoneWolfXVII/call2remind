package app.call2remind.sources.samsung

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.call2remind.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in notification listener (user grants "Notification access"): replaces Samsung Reminders'
 * own notification with a Call2Remind call. All logic is in [SamsungReminderHandler]; this service
 * only extracts [PostedNotification]s and cancels Samsung's notification once our ring is claimed.
 * Work runs on the application scope so a ring is never lost when the listener unbinds.
 */
@AndroidEntryPoint
class SamsungReminderListener : NotificationListenerService() {
    @Inject lateinit var handler: SamsungReminderHandler

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    @Volatile
    private var connected = false

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn?.toPosted() ?: return
        scope.launch {
            try {
                handler.onPosted(posted) { key -> cancelSafely(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle notification from ${posted.packageName}", e)
            }
        }
    }

    private fun cancelSafely(key: String) {
        if (!connected) return
        try {
            cancelNotification(key)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not cancel $key (listener unbound)", e)
        }
    }

    companion object {
        private const val TAG = "SamsungReminderListener"

        /** Extracts the fields the parser needs from a posted notification. */
        fun StatusBarNotification.toPosted(): PostedNotification {
            val n = notification
            val extras = n?.extras
            return PostedNotification(
                key = key,
                packageName = packageName,
                postTime = postTime,
                category = n?.category,
                channelId = n?.channelId,
                isGroupSummary = n != null && (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                isOngoing = isOngoing || (n != null && (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0),
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                bigTitle = extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            )
        }
    }
}

/** Whether the user granted this app notification access (needed by [SamsungReminderListener]). */
interface NotificationAccess {
    fun isGranted(): Boolean

    /** Component to highlight in `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`. */
    val listenerComponent: ComponentName
}

@Singleton
class AndroidNotificationAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationAccess {
    override fun isGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override val listenerComponent: ComponentName
        get() = ComponentName(context, SamsungReminderListener::class.java)
}
