package app.call2remind.ringing

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.core.ringing.RingMode
import app.call2remind.receivers.CallActionReceiver
import app.call2remind.testing.T0
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RingNotificationsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = RingNotifications(context)
    private val occ = occurrence(reminder("a", Schedule.At(T0)), T0)

    private fun grantPostNotifications() = shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun ensureChannelsCreatesAllChannelsIdempotently() {
        notifications.ensureChannels()
        notifications.ensureChannels()

        val calls = manager.getNotificationChannel(RingNotifications.CHANNEL_CALLS)
        assertThat(calls.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(calls.sound).isNull()
        // Alarm usage: DND treats the ringing notification as an alarm (not intercepted in
        // alarms-only / priority mode, where our USAGE_ALARM ringtone still plays).
        assertThat(calls.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(calls.shouldVibrate()).isFalse()
        assertThat(calls.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
        val fallback = manager.getNotificationChannel(RingNotifications.CHANNEL_CALLS_FALLBACK)
        assertThat(fallback.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(fallback.shouldVibrate()).isTrue()
        assertThat(fallback.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(manager.getNotificationChannel(RingNotifications.CHANNEL_MISSED)).isNotNull()
        assertThat(manager.getNotificationChannel(RingNotifications.CHANNEL_HINTS)).isNotNull()
        assertThat(manager.notificationChannels).hasSize(4)
    }

    @Test
    fun ensureChannelsReplacesTheLegacyCallsChannel() {
        manager.createNotificationChannel(
            NotificationChannel(RingNotifications.LEGACY_CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH),
        )

        notifications.ensureChannels()

        assertThat(RingNotifications.CHANNEL_CALLS).isNotEqualTo(RingNotifications.LEGACY_CHANNEL_CALLS)
        assertThat(manager.getNotificationChannel(RingNotifications.LEGACY_CHANNEL_CALLS)).isNull()
        assertThat(manager.notificationChannels.map { it.id }).containsExactly(
            RingNotifications.CHANNEL_CALLS,
            RingNotifications.CHANNEL_CALLS_FALLBACK,
            RingNotifications.CHANNEL_MISSED,
            RingNotifications.CHANNEL_HINTS,
        )
    }

    @Test
    fun incomingCallIsAnIncomingCallStyleWithFullScreenIntent() {
        val notification = notifications.incomingCall(occ.id, "Pay rent", RingMode.FULL_SCREEN)

        assertThat(notification.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notification.flags and Notification.FLAG_INSISTENT).isEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE)).isEqualTo(Notification.CallStyle::class.java.name)
        assertThat(notification.extras.getInt(Notification.EXTRA_CALL_TYPE)).isEqualTo(Notification.CallStyle.CALL_TYPE_INCOMING)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Pay rent")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        val fullScreen = shadowOf(notification.fullScreenIntent)
        assertThat(fullScreen.isActivity).isTrue()
        assertThat(fullScreen.savedIntent.component?.className).isEqualTo(IncomingCallActivity::class.java.name)
        assertThat(fullScreen.savedIntent.action).isEqualTo(IncomingCallActivity.ACTION_SHOW)
        assertThat(fullScreen.savedIntent.getStringExtra(IncomingCallActivity.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
        assertThat(notification.contentIntent).isNotNull()
    }

    @Suppress("DEPRECATION")
    private fun Notification.extraIntent(key: String) =
        shadowOf(requireNotNull(extras.getParcelable<PendingIntent>(key)) { "missing $key" }).savedIntent

    @Test
    fun declineSnoozesThroughTheActionReceiverAndAnswerOpensTheCallScreen() {
        val notification = notifications.incomingCall(occ.id, "Pay rent", RingMode.FULL_SCREEN)

        val decline = notification.extraIntent(Notification.EXTRA_DECLINE_INTENT)
        assertThat(decline.action).isEqualTo(CallActionReceiver.ACTION_DECLINE)
        assertThat(decline.component?.className).isEqualTo(CallActionReceiver::class.java.name)
        assertThat(decline.getStringExtra(CallActionReceiver.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
        val answer = notification.extraIntent(Notification.EXTRA_ANSWER_INTENT)
        assertThat(answer.action).isEqualTo(IncomingCallActivity.ACTION_ANSWER)
        assertThat(answer.getStringExtra(IncomingCallActivity.EXTRA_OCCURRENCE_ID)).isEqualTo(occ.id)
    }

    @Test
    fun degradedModeHasNoFullScreenIntentButFallbackAlwaysDoes() {
        assertThat(notifications.incomingCall(occ.id, "x", RingMode.HEADS_UP_DEGRADED).fullScreenIntent).isNull()
        assertThat(notifications.incomingCall(occ.id, "x", RingMode.SILENT_FULL_SCREEN_VIBRATE).fullScreenIntent).isNotNull()

        val fallback = notifications.incomingCall(occ.id, "x", RingMode.HEADS_UP_DEGRADED, fallback = true)
        assertThat(fallback.fullScreenIntent).isNotNull()
        assertThat(fallback.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS_FALLBACK)
        assertThat(fallback.category).isEqualTo(Notification.CATEGORY_ALARM)
        // No service plays the ringtone: the channel sound must loop until the ring ends.
        assertThat(fallback.flags and Notification.FLAG_INSISTENT).isNotEqualTo(0)
    }

    @Test
    fun outsideAForegroundServiceACallStyleAlwaysHasAFullScreenIntent() {
        // A CallStyle notification posted outside an FGS without a full-screen intent is rejected
        // (IllegalArgumentException) by the system, so the service's fallback path forces one.
        val outside = notifications.incomingCall(occ.id, "x", RingMode.HEADS_UP_DEGRADED, requireFullScreen = true)
        assertThat(outside.fullScreenIntent).isNotNull()
        assertThat(outside.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS)
        assertThat(outside.flags and Notification.FLAG_INSISTENT).isEqualTo(0)

        val plainOngoing = notifications.ongoingCall(occ.id, "Pay rent", callStyle = false)
        assertThat(plainOngoing.extras.getString(Notification.EXTRA_TEMPLATE)).isNotEqualTo(Notification.CallStyle::class.java.name)
        val actions = plainOngoing.actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent.action }
        assertThat(actions).containsExactly(CallActionReceiver.ACTION_DONE, CallActionReceiver.ACTION_SNOOZE).inOrder()
    }

    @Test
    @Config(sdk = [29])
    fun ringingNotificationsAreAlarmsAlsoWhereCompatCallStyleSetsCategoryCall() {
        assertThat(notifications.incomingCall(occ.id, "x", RingMode.FULL_SCREEN).category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notifications.ongoingCall(occ.id, "x").category).isEqualTo(Notification.CATEGORY_ALARM)
    }

    @Test
    fun blankTitlesUseTheFallbackTitle() {
        val notification = notifications.incomingCall(occ.id, "  ", RingMode.FULL_SCREEN)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Reminder")
    }

    @Test
    fun ongoingCallIsSilentWithHangUpAsDone() {
        val notification = notifications.ongoingCall(occ.id, "Pay rent")

        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notification.extras.getInt(Notification.EXTRA_CALL_TYPE)).isEqualTo(Notification.CallStyle.CALL_TYPE_ONGOING)
        assertThat(notification.extraIntent(Notification.EXTRA_HANG_UP_INTENT).action).isEqualTo(CallActionReceiver.ACTION_DONE)
        val actions = notification.actions.orEmpty().map { shadowOf(it.actionIntent).savedIntent.action }
        assertThat(actions).contains(CallActionReceiver.ACTION_SNOOZE)
    }

    @Test
    fun missedNotificationSaysWhenItRingsAgainOrThatItGaveUp() {
        val snoozed = occ.copy(state = OccurrenceState.SNOOZED, fireAt = T0.plus(minutes(5)))
        val again = notifications.missed(snoozed, "Pay rent", ZoneOffset.UTC)
        assertThat(again.channelId).isEqualTo(RingNotifications.CHANNEL_MISSED)
        assertThat(again.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Missed reminder: Pay rent")
        assertThat(again.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).startsWith("Calling again at ")

        val gaveUp = notifications.missed(occ.copy(state = OccurrenceState.MISSED), "", ZoneOffset.UTC)
        assertThat(gaveUp.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("You missed this reminder")
        val done = shadowOf(gaveUp.actions.single().actionIntent).savedIntent
        assertThat(done.action).isEqualTo(CallActionReceiver.ACTION_DONE)
    }

    @Test
    fun deferredAndDndHintNotificationsAreSilent() {
        val deferred = notifications.deferred(listOf("A", " ", "B"))
        assertThat(deferred.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Will ring when your call ends: A, B")
        assertThat(notifications.deferred(emptyList()).extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Will ring when your call ends: Reminder")
        assertThat(notifications.dndHint().channelId).isEqualTo(RingNotifications.CHANNEL_HINTS)
    }

    @Test
    fun notifyRespectsThePostNotificationsPermission() {
        notifications.ensureChannels()
        assertThat(notifications.canPostNotifications()).isFalse()
        assertThat(notifications.notify(1, notifications.dndHint())).isFalse()
        assertThat(shadowOf(manager).allNotifications).isEmpty()

        grantPostNotifications()
        assertThat(notifications.canPostNotifications()).isTrue()
        assertThat(notifications.notify(1, notifications.dndHint(), RingNotifications.TAG_MISSED)).isTrue()
        assertThat(shadowOf(manager).getNotification(RingNotifications.TAG_MISSED, 1)).isNotNull()

        notifications.cancel(1, RingNotifications.TAG_MISSED)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun perOccurrenceIdsAreStable() {
        assertThat(notifications.missedId(occ)).isEqualTo(occ.requestCode)
        assertThat(notifications.fallbackId(occ)).isEqualTo(occ.requestCode)
    }

    @Test
    fun ringIdsAreStablePerOccurrenceDistinctAndNeverAFixedId() {
        val other = occurrence(reminder("b", Schedule.At(T0)), T0)

        assertThat(notifications.ringId(occ.id)).isEqualTo(notifications.ringId(occ.id))
        assertThat(notifications.ringId(occ.id)).isEqualTo(occ.requestCode)
        assertThat(notifications.ringId(other.id)).isNotEqualTo(notifications.ringId(occ.id))
        val ids = (1..2_000).map { notifications.ringId("occ-$it") }
        assertThat(ids).containsNoneOf(RingNotifications.RING_NOTIFICATION_ID, RingNotifications.DND_HINT_ID)
    }
}
