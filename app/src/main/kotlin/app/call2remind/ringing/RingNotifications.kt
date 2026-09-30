package app.call2remind.ringing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.call2remind.MainActivity
import app.call2remind.R
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.planning.RequestCodes
import app.call2remind.core.ringing.RingMode
import app.call2remind.receivers.CallActionReceiver
import app.call2remind.scheduling.AndroidAlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts every notification of the ringing flow.
 *
 * **Do Not Disturb:** ringing notifications are alarms, not calls. A `CATEGORY_CALL` notification
 * from a non-starred caller is intercepted in priority mode (no heads-up, no full-screen intent)
 * and always in alarms-only mode, while our `USAGE_ALARM` ringtone would still play. So every
 * ringing notification uses `CATEGORY_ALARM` (forced after building: `NotificationCompat.CallStyle`
 * sets `CATEGORY_CALL` on API < 31) and the calls channel carries `USAGE_ALARM` audio attributes
 * (with no sound: the service plays the ringtone). Either marks it as an alarm for DND.
 * CallStyle is kept for its call-like look; it does not require `CATEGORY_CALL`.
 */
@Singleton
class RingNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    /**
     * Creates the notification channels (idempotent) and deletes superseded ones. Channels are
     * immutable once created, so changing the calls channel's audio attributes needed a new id
     * ([CHANNEL_CALLS]); the old one ([LEGACY_CHANNEL_CALLS]) is deleted here.
     */
    fun ensureChannels() {
        val calls = NotificationChannel(CHANNEL_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_calls_description)
            // The ringing service plays the ringtone and vibrates itself (USAGE_ALARM, looping).
            // No sound here, but alarm usage so DND treats the ringing notification as an alarm.
            setSound(null, ALARM_ATTRIBUTES)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val fallback = NotificationChannel(
            CHANNEL_CALLS_FALLBACK,
            context.getString(R.string.channel_calls_fallback),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_calls_fallback_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), ALARM_ATTRIBUTES)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.channel_missed_description) }
        val hints = NotificationChannel(CHANNEL_HINTS, context.getString(R.string.channel_hints), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.channel_hints_description) }
        manager.deleteNotificationChannel(LEGACY_CHANNEL_CALLS)
        manager.createNotificationChannels(listOf(calls, fallback, missed, hints))
    }

    /**
     * Incoming-call notification: `CallStyle.forIncomingCall` with Answer (opens the call screen)
     * and Decline (snooze), `CATEGORY_ALARM` (see the class doc). A full-screen intent is attached
     * unless [mode] is [RingMode.HEADS_UP_DEGRADED] and [requireFullScreen] is false.
     *
     * [requireFullScreen]: the notification is posted outside a foreground service, where a
     * CallStyle notification MUST have a full-screen intent (else `notify` throws); without the
     * permission the system just strips it. [fallback] (no ringing service at all) implies it and
     * uses the sounding, insistent channel.
     */
    fun incomingCall(
        occurrenceId: String,
        title: String,
        mode: RingMode,
        fallback: Boolean = false,
        requireFullScreen: Boolean = fallback,
    ): Notification {
        val name = title.ifBlank { context.getString(R.string.reminder_fallback_title) }
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, if (fallback) CHANNEL_CALLS_FALLBACK else CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(name)
            .setContentText(context.getString(R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // Each occurrence rings under its own id (see ringId), so this only silences re-posts
            // of the same ring, never the next queued call.
            .setOnlyAlertOnce(true)
            .setContentIntent(callScreenIntent(occurrenceId, answer = false))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    actionIntent(occurrenceId, CallActionReceiver.ACTION_DECLINE),
                    callScreenIntent(occurrenceId, answer = true),
                ),
            )
        if (fallback || requireFullScreen || mode != RingMode.HEADS_UP_DEGRADED) {
            builder.setFullScreenIntent(callScreenIntent(occurrenceId, answer = false), true)
        }
        return builder.build().asAlarm().apply {
            // No service plays the ringtone: the channel sound loops until the ring ends.
            if (fallback) flags = flags or Notification.FLAG_INSISTENT
        }
    }

    /**
     * After Answer: ongoing-call style with hang-up = Done, plus Snooze. With [callStyle] false
     * (posted outside a foreground service, where an ongoing CallStyle is not allowed) it is a
     * plain notification with Done and Snooze actions.
     */
    fun ongoingCall(occurrenceId: String, title: String, callStyle: Boolean = true): Notification {
        val name = title.ifBlank { context.getString(R.string.reminder_fallback_title) }
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(name)
            .setContentText(context.getString(R.string.call_in_progress))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(callScreenIntent(occurrenceId, answer = false))
        if (callStyle) {
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, actionIntent(occurrenceId, CallActionReceiver.ACTION_DONE)))
        } else {
            builder.addAction(0, context.getString(R.string.action_done), actionIntent(occurrenceId, CallActionReceiver.ACTION_DONE))
        }
        return builder
            .addAction(0, context.getString(R.string.action_snooze), actionIntent(occurrenceId, CallActionReceiver.ACTION_SNOOZE))
            .build()
            .asAlarm()
    }

    /** Silent heads-up while the user is in a real call. */
    fun deferred(titles: List<String>): Notification {
        val names = titles.filter { it.isNotBlank() }.ifEmpty { listOf(context.getString(R.string.reminder_fallback_title)) }
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.call_deferred_title))
            .setContentText(context.getString(R.string.call_deferred_text, names.joinToString()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())
            .build()
    }

    /** "Missed reminder" (auto-snoozed: "calling again at …"; missed: final). */
    fun missed(occurrence: Occurrence, title: String, zone: ZoneId = ZoneId.systemDefault()): Notification {
        val name = title.ifBlank { context.getString(R.string.reminder_fallback_title) }
        val text = if (occurrence.state == OccurrenceState.SNOOZED) {
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(occurrence.fireAt.atZone(zone))
            context.getString(R.string.missed_ring_again, time)
        } else {
            context.getString(R.string.missed_gave_up)
        }
        return NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.missed_title) + ": " + name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(mainIntent())
            .addAction(0, context.getString(R.string.action_done), actionIntent(occurrence.id, CallActionReceiver.ACTION_DONE))
            .build()
    }

    /** One-time hint that calls are silent while DND blocks everything. */
    fun dndHint(): Notification = NotificationCompat.Builder(context, CHANNEL_HINTS)
        .setSmallIcon(R.drawable.ic_stat_call)
        .setContentTitle(context.getString(R.string.dnd_hint_title))
        .setContentText(context.getString(R.string.dnd_hint_text))
        .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.dnd_hint_text)))
        .setAutoCancel(true)
        .setContentIntent(mainIntent())
        .build()

    /**
     * Posts [notification] if the app may post notifications (POST_NOTIFICATIONS on API 33+).
     * Never throws: a notification the system rejects (e.g. a CallStyle outside a foreground
     * service without a full-screen intent) is logged. Returns true if it was posted.
     */
    @SuppressLint("MissingPermission") // Checked right here.
    fun notify(id: Int, notification: Notification, tag: String? = null): Boolean {
        if (!canPostNotifications()) return false
        return try {
            manager.notify(tag, id, notification)
            true
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Notification $id rejected", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification $id not allowed", e)
            false
        }
    }

    fun cancel(id: Int, tag: String? = null) {
        manager.cancel(tag, id)
    }

    fun missedId(occurrence: Occurrence): Int = occurrence.requestCode

    /** Id (with [TAG_FALLBACK]) of the ringing notification posted when the service cannot start. */
    fun fallbackId(occurrence: Occurrence): Int = occurrence.requestCode

    /**
     * Untagged id of the ringing service's notification for [occurrenceId]. Distinct per
     * occurrence, so the next queued call is a NEW notification (heads-up + full-screen intent)
     * rather than a silent update of the previous one; never one of the fixed ids.
     */
    fun ringId(occurrenceId: String): Int {
        val code = RequestCodes.forOccurrence(occurrenceId)
        return if (code in FIXED_IDS) code + FIXED_IDS.last + 1 else code
    }

    /** True if notifications can be posted (POST_NOTIFICATIONS granted on API 33+). */
    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun callScreenIntent(occurrenceId: String, answer: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (answer) REQUEST_ANSWER else REQUEST_SHOW,
        IncomingCallActivity.intent(context, occurrenceId, answer),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(occurrenceId: String, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ACTION,
        Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .setData(AndroidAlarmScheduler.occurrenceUri(occurrenceId))
            .putExtra(CallActionReceiver.EXTRA_OCCURRENCE_ID, occurrenceId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_MAIN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Marks a built ringing notification as an alarm for DND (see the class doc). */
    private fun Notification.asAlarm(): Notification = apply { category = Notification.CATEGORY_ALARM }

    companion object {
        private const val TAG = "RingNotifications"

        /**
         * Silent, high-importance channel of ringing notifications, with `USAGE_ALARM` audio
         * attributes. `calls_v2` replaced [LEGACY_CHANNEL_CALLS] (created without them).
         */
        const val CHANNEL_CALLS = "calls_v2"
        const val LEGACY_CHANNEL_CALLS = "calls"
        const val CHANNEL_CALLS_FALLBACK = "calls_fallback"
        const val CHANNEL_MISSED = "missed"
        const val CHANNEL_HINTS = "hints"

        /**
         * Id of the deferred ("will ring when your call ends") and idle foreground-service
         * notification. A ringing call uses [ringId] instead.
         */
        const val RING_NOTIFICATION_ID = 1001
        const val DND_HINT_ID = 1002
        const val TAG_MISSED = "missed"
        const val TAG_FALLBACK = "fallback"

        private val FIXED_IDS = 0..DND_HINT_ID

        private val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        private const val REQUEST_SHOW = 1
        private const val REQUEST_ANSWER = 2
        private const val REQUEST_ACTION = 3
        private const val REQUEST_MAIN = 4
    }
}
