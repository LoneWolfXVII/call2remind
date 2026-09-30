package app.call2remind.ringing

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
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
        assertThat(calls.shouldVibrate()).isFalse()
        assertThat(calls.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PUBLIC)
        val fallback = manager.getNotificationChannel(RingNotifications.CHANNEL_CALLS_FALLBACK)
        assertThat(fallback.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(fallback.shouldVibrate()).isTrue()
        assertThat(manager.getNotificationChannel(RingNotifications.CHANNEL_MISSED)).isNotNull()
        assertThat(manager.getNotificationChannel(RingNotifications.CHANNEL_HINTS)).isNotNull()
        assertThat(manager.notificationChannels).hasSize(4)
    }

    @Test
    fun incomingCallIsAnIncomingCallStyleWithFullScreenIntent() {
        val notification = notifications.incomingCall(occ.id, "Pay rent", RingMode.FULL_SCREEN)

        assertThat(notification.channelId).isEqualTo(RingNotifications.CHANNEL_CALLS)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_CALL)
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
    }

    @Test
    fun blankTitlesUseTheFallbackTitle() {
        val notification = notifications.incomingCall(occ.id, "  ", RingMode.FULL_SCREEN)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Reminder")
    }

    @Test
    fun ongoingCallIsSilentWithHangUpAsDone() {
        val notification = notifications.ongoingCall(occ.id, "Pay rent")

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
        notifications.notify(1, notifications.dndHint())
        assertThat(shadowOf(manager).allNotifications).isEmpty()

        grantPostNotifications()
        notifications.notify(1, notifications.dndHint(), RingNotifications.TAG_MISSED)
        assertThat(shadowOf(manager).getNotification(RingNotifications.TAG_MISSED, 1)).isNotNull()

        notifications.cancel(1, RingNotifications.TAG_MISSED)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun perOccurrenceIdsAreStable() {
        assertThat(notifications.missedId(occ)).isEqualTo(occ.requestCode)
        assertThat(notifications.fallbackId(occ)).isEqualTo(occ.requestCode)
    }
}
