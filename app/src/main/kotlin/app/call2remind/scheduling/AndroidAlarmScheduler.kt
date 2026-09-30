package app.call2remind.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import app.call2remind.MainActivity
import app.call2remind.core.model.Occurrence
import app.call2remind.receivers.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AlarmScheduler] on [AlarmManager].
 *
 * - Soonest occurrence: `setAlarmClock` (exempt from Doze, grants FGS-start from the receiver).
 * - Others: `setExactAndAllowWhileIdle`.
 * - Without exact-alarm access (API 31–32 with SCHEDULE_EXACT_ALARM revoked):
 *   `setAndAllowWhileIdle` (may be deferred by a few minutes; watchdog covers the gap).
 *
 * The broadcast intent carries a per-occurrence data URI, so PendingIntents are distinct per
 * occurrence even if two request codes ever collided.
 */
@Singleton
class AndroidAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmScheduler {

    private val alarmManager: AlarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))

    override fun arm(occurrence: Occurrence, triggerAt: Instant, isSoonest: Boolean) {
        val operation = pendingIntent(occurrence, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = triggerAt.toEpochMilli()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                if (isSoonest) {
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), operation)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                }
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm refused, falling back to inexact", e)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    override fun cancel(occurrence: Occurrence) {
        val operation = pendingIntent(occurrence, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun pendingIntent(occurrence: Occurrence, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            occurrence.requestCode,
            fireIntent(context, occurrence.id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val SHOW_REQUEST_CODE = 0

        /** The broadcast an occurrence alarm delivers to [AlarmReceiver]. */
        fun fireIntent(context: Context, occurrenceId: String): Intent =
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData(occurrenceUri(occurrenceId))
                .putExtra(AlarmReceiver.EXTRA_OCCURRENCE_ID, occurrenceId)

        /** `call2remind://occurrence/<id>` — makes intents unique per occurrence. */
        fun occurrenceUri(occurrenceId: String): Uri =
            Uri.Builder().scheme("call2remind").authority("occurrence").appendPath(occurrenceId).build()
    }
}
