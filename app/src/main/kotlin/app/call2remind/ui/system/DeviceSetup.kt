package app.call2remind.ui.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Everything onboarding asks the user to allow. */
enum class SetupItem {
    NOTIFICATIONS,
    FULL_SCREEN,
    EXACT_ALARMS,
    CALENDAR,
    CONTACTS,
}

/**
 * How a [SetupItem] gets granted on this device.
 * - [RUNTIME]: a runtime permission dialog.
 * - [SETTINGS]: only a system settings page can grant it.
 * - [AUTOMATIC]: granted by the platform on this API level (no row action needed).
 */
enum class GrantPath { RUNTIME, SETTINGS, AUTOMATIC }

/** A snapshot of the device's setup state. */
data class DeviceSetupState(
    val granted: Set<SetupItem> = emptySet(),
    val paths: Map<SetupItem, GrantPath> = emptyMap(),
    /** The app is exempt from battery optimization (Doze whitelist). */
    val batteryUnrestricted: Boolean = false,
    /** Samsung phones get the Device Care "Never sleeping apps" step. */
    val isSamsung: Boolean = false,
) {
    fun isGranted(item: SetupItem): Boolean = item in granted

    fun pathOf(item: SetupItem): GrantPath = paths[item] ?: GrantPath.SETTINGS

    /** How many of the [SetupItem]s are granted. */
    val grantedCount: Int get() = SetupItem.entries.count(::isGranted)
}

/** Reads the device's permission / exemption state. Faked in tests. */
interface DeviceSetupChecker {
    fun check(): DeviceSetupState

    /** The runtime permission behind [item], or `null` if it is not a runtime permission here. */
    fun runtimePermission(item: SetupItem): String?
}

/** [DeviceSetupChecker] on the real platform APIs. */
class AndroidDeviceSetupChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceSetupChecker {

    override fun check(): DeviceSetupState {
        val sdk = Build.VERSION.SDK_INT
        val paths = mapOf(
            SetupItem.NOTIFICATIONS to if (sdk >= Build.VERSION_CODES.TIRAMISU) GrantPath.RUNTIME else GrantPath.SETTINGS,
            SetupItem.FULL_SCREEN to if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) GrantPath.SETTINGS else GrantPath.AUTOMATIC,
            // USE_EXACT_ALARM (33+) is granted at install; SCHEDULE_EXACT_ALARM (31-32) needs the settings page.
            SetupItem.EXACT_ALARMS to if (sdk in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2) GrantPath.SETTINGS else GrantPath.AUTOMATIC,
            SetupItem.CALENDAR to GrantPath.RUNTIME,
            SetupItem.CONTACTS to GrantPath.RUNTIME,
        )
        val granted = buildSet {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) add(SetupItem.NOTIFICATIONS)
            if (canUseFullScreenIntent()) add(SetupItem.FULL_SCREEN)
            if (canScheduleExactAlarms()) add(SetupItem.EXACT_ALARMS)
            if (has(Manifest.permission.READ_CALENDAR)) add(SetupItem.CALENDAR)
            if (has(Manifest.permission.READ_CONTACTS)) add(SetupItem.CONTACTS)
        }
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceSetupState(
            granted = granted,
            paths = paths,
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
    }

    override fun runtimePermission(item: SetupItem): String? = when (item) {
        SetupItem.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        SetupItem.CALENDAR -> Manifest.permission.READ_CALENDAR
        SetupItem.CONTACTS -> Manifest.permission.READ_CONTACTS
        SetupItem.FULL_SCREEN, SetupItem.EXACT_ALARMS -> null
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun canUseFullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

    private fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
}
