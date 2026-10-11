package app.call2remind.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single source of truth for reminders, occurrences and the ring log.
 *
 * ### Direct boot
 * [create] opens the database in **device-protected (DE) storage**
 * (`createDeviceProtectedStorageContext()`), so `LOCKED_BOOT_COMPLETED`, alarms and the ringing
 * service can read the 48 h window and ring before the user first unlocks after a reboot.
 *
 * Tradeoff: DE storage is encrypted with a device-bound key that is available right after boot,
 * not with the user's credential. Reminder titles/notes are therefore protected only as well as
 * the device itself before first unlock (e.g. against a powered-off device, but not against an
 * attacker with a booted, still-locked device and an OS exploit). Tokens and anything sensitive
 * must NOT go in this database — keep them in credential-protected, Keystore-backed storage.
 * Reminder content is judged acceptable here because never missing a ring is the product.
 */
@Database(
    entities = [SourceEntity::class, ReminderEntity::class, OccurrenceEntity::class, RingLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class Call2RemindDb : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun reminderDao(): ReminderDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun ringLogDao(): RingLogDao

    companion object {
        const val NAME: String = "call2remind.db"

        /** Opens the on-disk database in device-protected storage (see class docs). */
        fun create(context: Context): Call2RemindDb {
            val deviceProtected = context.applicationContext.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(deviceProtected, Call2RemindDb::class.java, NAME).build()
        }
    }
}
