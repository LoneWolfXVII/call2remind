package app.call2remind

import android.app.Application
import androidx.core.os.UserManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.call2remind.di.ApplicationScope
import app.call2remind.ringing.AppForegroundTracker
import app.call2remind.ringing.RingNotifications
import app.call2remind.scheduling.ReplanReason
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.SettingsRepository
import app.call2remind.work.BackgroundJobs
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * On every process start: replan + recovery + re-arm (idempotent), schedule the watchdog / daily replan
 * (only once the user is unlocked — WorkManager lives in credential-protected storage), and
 * replan whenever the per-source default times change.
 */
@HiltAndroidApp
class Call2RemindApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var engine: SchedulingEngine

    @Inject lateinit var jobs: BackgroundJobs

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var foregroundTracker: AppForegroundTracker

    @Inject lateinit var notifications: RingNotifications

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        foregroundTracker.register(this)
        notifications.ensureChannels()
        scope.launch { engine.onAppStart() }
        scope.launch {
            settings.settings
                .map { it.defaultTimes }
                .distinctUntilChanged()
                .drop(1)
                .collect { engine.replan(ReplanReason.SETTINGS_CHANGED) }
        }
        if (UserManagerCompat.isUserUnlocked(this)) jobs.ensureScheduled()
    }
}
