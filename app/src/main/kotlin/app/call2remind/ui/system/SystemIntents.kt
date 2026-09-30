package app.call2remind.ui.system

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import app.call2remind.core.model.SourceType
import java.time.Instant

/**
 * Deep links into system settings and other apps. Each returns candidates in preference order;
 * [launchFirst] starts the first one that resolves. No `<queries>` are needed: starting an
 * activity is not subject to package-visibility filtering, so we just try and fall through.
 */
object SystemIntents {
    private const val TAG = "SystemIntents"

    fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Android 14+: the per-app "Full screen intents" switch. */
    fun fullScreenIntent(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
        }
        add(appNotificationSettings(context))
        add(appDetails(context))
    }

    /** Android 12–12L: "Alarms & reminders" special access. */
    fun exactAlarms(context: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
        }
        add(appDetails(context))
    }

    fun notifications(context: Context): List<Intent> = listOf(appNotificationSettings(context), appDetails(context))

    /** The "Allow app to always run in background?" dialog, then the full optimization list. */
    @SuppressLint("BatteryLife") // Reminder-call app: exact on-time alarms are its core function.
    fun batteryOptimization(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    /**
     * Samsung Device Care → Battery (where "Background usage limits → Never sleeping apps" lives).
     * Component names moved between One UI versions, so try the known ones, then app details.
     */
    fun samsungDeviceCare(context: Context): List<Intent> = listOf(
        component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        component("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    /**
     * Opens the item in its source app, when there is one to open (habits live here). Returns the
     * candidates, or an empty list if the source has no app.
     */
    fun openSource(source: SourceType, at: Instant?): List<Intent> = when (source) {
        SourceType.CALENDAR -> listOfNotNull(
            at?.let {
                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(it.toEpochMilli().toString()).build())
            },
            launcher("com.google.android.calendar"),
            launcher("com.samsung.android.calendar"),
        )
        SourceType.GOOGLE_TASKS -> listOf(launcher("com.google.android.apps.tasks"))
        SourceType.SAMSUNG_REMINDER -> listOf(launcher("com.samsung.android.app.reminder"))
        SourceType.MS_TODO -> listOf(launcher("com.microsoft.todos"))
        SourceType.BIRTHDAY -> listOf(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        SourceType.HABIT -> emptyList()
    }

    /** Starts the first of [candidates] that resolves. Returns false if none did. */
    fun launchFirst(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "No activity for $intent")
            } catch (e: SecurityException) {
                Log.d(TAG, "Not allowed to start $intent", e)
            }
        }
        return false
    }

    private fun component(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))

    private fun launcher(pkg: String): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
}
