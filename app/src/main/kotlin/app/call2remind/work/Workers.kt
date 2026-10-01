package app.call2remind.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.call2remind.scheduling.SchedulingEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every 15 minutes: [SchedulingEngine.runWatchdog] — overdue SCHEDULED/SNOOZED rings < 2 h late
 * get an immediate alarm (the alarm receiver rings them), older ones are marked missed, stuck
 * rings time out; then every pending alarm is re-armed.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SchedulingEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        engine.runWatchdog()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Watchdog failed", e)
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "watchdog"
        private const val TAG = "WatchdogWorker"
    }
}

/** Daily top-up: replans the 48 h window and prunes old history. */
@HiltWorker
class DailyReplanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SchedulingEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        engine.dailyTopUp()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Daily replan failed", e)
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "daily_replan"
        private const val TAG = "DailyReplanWorker"
    }
}

/** Schedules the periodic background jobs. */
interface BackgroundJobs {
    /** Enqueues the watchdog and daily replan (unique, KEEP). Requires an unlocked user. */
    fun ensureScheduled()
}

@Singleton
class WorkManagerBackgroundJobs @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundJobs {
    override fun ensureScheduled() {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            WatchdogWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_MINUTES, TimeUnit.MINUTES).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            DailyReplanWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyReplanWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    private companion object {
        const val WATCHDOG_MINUTES = 15L
    }
}
