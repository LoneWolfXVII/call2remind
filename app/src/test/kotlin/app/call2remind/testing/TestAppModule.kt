package app.call2remind.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.di.AppModule
import app.call2remind.di.ApplicationScope
import app.call2remind.di.DataStoreModule
import app.call2remind.di.DatabaseModule
import app.call2remind.di.DefaultDispatcher
import app.call2remind.di.IoDispatcher
import app.call2remind.di.SystemModule
import app.call2remind.ringing.CallStateMonitor
import app.call2remind.ringing.RingAlerts
import app.call2remind.ringing.RingContextProvider
import app.call2remind.ringing.TtsPlayer
import app.call2remind.scheduling.AlarmScheduler
import app.call2remind.work.BackgroundJobs
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import java.time.Clock
import javax.inject.Singleton

/**
 * Replaces every Android-system-facing binding for `@HiltAndroidTest`s: an in-memory database,
 * a throwaway DataStore file, a [MutableClock] and fakes. Repositories, the scheduling engine,
 * notifications, the ring launcher and the missed notifier stay real.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class, DatabaseModule::class, DataStoreModule::class, SystemModule::class],
)
object TestAppModule {
    @Provides
    @Singleton
    fun mutableClock(): MutableClock = MutableClock()

    @Provides
    fun clock(clock: MutableClock): Clock = clock

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): Call2RemindDb =
        Room.inMemoryDatabaseBuilder(context, Call2RemindDb::class.java).build()

    @Provides
    @Singleton
    fun dataStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("settings").toFile().apply { deleteOnExit() }
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(dir, "settings.preferences_pb")
        }
    }

    @Provides
    @Singleton
    fun fakeAlarms(): FakeAlarmScheduler = FakeAlarmScheduler()

    @Provides
    fun alarms(fake: FakeAlarmScheduler): AlarmScheduler = fake

    @Provides
    @Singleton
    fun fakeRingContext(): FakeRingContextProvider = FakeRingContextProvider()

    @Provides
    fun ringContext(fake: FakeRingContextProvider): RingContextProvider = fake

    @Provides
    @Singleton
    fun fakeCallState(): FakeCallStateMonitor = FakeCallStateMonitor()

    @Provides
    fun callState(fake: FakeCallStateMonitor): CallStateMonitor = fake

    @Provides
    @Singleton
    fun fakeAlerts(): FakeRingAlerts = FakeRingAlerts()

    @Provides
    fun alerts(fake: FakeRingAlerts): RingAlerts = fake

    @Provides
    @Singleton
    fun fakeTts(): FakeTtsPlayer = FakeTtsPlayer()

    @Provides
    fun tts(fake: FakeTtsPlayer): TtsPlayer = fake

    @Provides
    @Singleton
    fun fakeJobs(): FakeBackgroundJobs = FakeBackgroundJobs()

    @Provides
    fun jobs(fake: FakeBackgroundJobs): BackgroundJobs = fake
}
