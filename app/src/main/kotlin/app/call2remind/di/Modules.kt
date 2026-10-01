package app.call2remind.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.call2remind.core.time.DeviceClock
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.OccurrenceRepository
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.data.repo.RoomOccurrenceRepository
import app.call2remind.data.repo.RoomReminderRepository
import app.call2remind.data.repo.RoomSourceRepository
import app.call2remind.data.repo.SourceRepository
import app.call2remind.ringing.AndroidCallStateMonitor
import app.call2remind.ringing.AndroidMissedNotifier
import app.call2remind.ringing.AndroidRingAlerts
import app.call2remind.ringing.AndroidRingContextProvider
import app.call2remind.ringing.AndroidRingLauncher
import app.call2remind.ringing.AndroidTtsPlayer
import app.call2remind.ringing.CallStateMonitor
import app.call2remind.ringing.RingAlerts
import app.call2remind.ringing.RingContextProvider
import app.call2remind.ringing.RingLauncher
import app.call2remind.ringing.TtsPlayer
import app.call2remind.scheduling.AlarmScheduler
import app.call2remind.scheduling.AndroidAlarmScheduler
import app.call2remind.scheduling.MissedNotifier
import app.call2remind.settings.DataStoreSettingsRepository
import app.call2remind.settings.SettingsRepository
import app.call2remind.work.BackgroundJobs
import app.call2remind.work.WorkManagerBackgroundJobs
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that must outlive a component (receivers, app start). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * The device clock. Its zone is read at every call ([DeviceClock]), never frozen at process
     * start, so zone-dependent code (new habits, wall-clock expansion, spoken times, day grouping)
     * follows TIMEZONE_CHANGED without a process restart.
     */
    @Provides
    @Singleton
    fun clock(): Clock = DeviceClock()

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e("ApplicationScope", "Uncaught", e) },
        )
}

/** The Room database, in device-protected storage (see [Call2RemindDb]). */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): Call2RemindDb = Call2RemindDb.create(context)
}

/** Settings DataStore, in device-protected storage so ringing works before first unlock. */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    private const val SETTINGS_FILE = "settings"

    @Provides
    @Singleton
    fun settingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> {
        val deviceProtected = context.applicationContext.createDeviceProtectedStorageContext()
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(io + SupervisorJob()),
            // Not `deviceProtected.preferencesDataStoreFile(...)`: that resolves against
            // `applicationContext.filesDir`, i.e. credential-protected storage, unreadable before
            // first unlock.
            produceFile = { File(deviceProtected.filesDir, "datastore/$SETTINGS_FILE.preferences_pb") },
        )
    }
}

/** Repositories and other app-internal bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun reminders(impl: RoomReminderRepository): ReminderRepository

    @Binds
    abstract fun occurrences(impl: RoomOccurrenceRepository): OccurrenceRepository

    @Binds
    abstract fun sources(impl: RoomSourceRepository): SourceRepository

    @Binds
    abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun missedNotifier(impl: AndroidMissedNotifier): MissedNotifier

    @Binds
    abstract fun ringLauncher(impl: AndroidRingLauncher): RingLauncher
}

/** Android system-facing bindings; tests replace this module with fakes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SystemModule {
    @Binds
    abstract fun alarmScheduler(impl: AndroidAlarmScheduler): AlarmScheduler

    @Binds
    abstract fun ringContextProvider(impl: AndroidRingContextProvider): RingContextProvider

    @Binds
    abstract fun callStateMonitor(impl: AndroidCallStateMonitor): CallStateMonitor

    @Binds
    abstract fun ringAlerts(impl: AndroidRingAlerts): RingAlerts

    @Binds
    abstract fun ttsPlayer(impl: AndroidTtsPlayer): TtsPlayer

    @Binds
    abstract fun backgroundJobs(impl: WorkManagerBackgroundJobs): BackgroundJobs
}
