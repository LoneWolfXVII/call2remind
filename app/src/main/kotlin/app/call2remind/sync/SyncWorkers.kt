package app.call2remind.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [SyncCoordinator.sync] for one [SyncScope]. `Result.retry()` when a source failed
 * retryably (WorkManager applies exponential backoff), up to [MAX_ATTEMPTS] runs; a failing source
 * never fails the whole job, and the periodic job simply tries again next period.
 */
internal suspend fun runSync(
    coordinator: SyncCoordinator,
    scope: SyncScope,
    force: Boolean,
    attempt: Int,
    tag: String,
): ListenableWorker.Result =
    try {
        val result = coordinator.sync(scope, force)
        if (result.needsRetry && attempt < SyncWorkers.MAX_ATTEMPTS) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.success()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(tag, "Sync failed", e)
        if (attempt < SyncWorkers.MAX_ATTEMPTS) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.failure()
        }
    }

/** Device sources (calendar, birthdays): no constraints. */
@HiltWorker
class LocalSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runSync(coordinator, SyncScope.LOCAL, inputData.getBoolean(SyncWorkers.KEY_FORCE, false), runAttemptCount, TAG)

    private companion object {
        const val TAG = "LocalSyncWorker"
    }
}

/** Cloud sources (Google Tasks, Microsoft To Do): scheduled with a CONNECTED constraint. */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runSync(coordinator, SyncScope.CLOUD, inputData.getBoolean(SyncWorkers.KEY_FORCE, false), runAttemptCount, TAG)

    private companion object {
        const val TAG = "CloudSyncWorker"
    }
}

/** Unique work names and keys. */
object SyncWorkers {
    const val PERIODIC_LOCAL: String = "sync_local_periodic"
    const val PERIODIC_CLOUD: String = "sync_cloud_periodic"
    const val NOW_LOCAL: String = "sync_local_now"
    const val NOW_CLOUD: String = "sync_cloud_now"
    const val KEY_FORCE: String = "force"
    const val PERIOD_MINUTES: Long = 15
    const val BACKOFF_SECONDS: Long = 30
    const val MAX_ATTEMPTS: Int = 5
}

/** Schedules sync work. */
interface SyncScheduler {
    /** Enqueues the 15-minute periodic local and cloud syncs (unique, KEEP). Needs an unlocked user. */
    fun ensurePeriodic()

    /**
     * Enqueues a one-shot forced sync of [scope] now (expedited on Android 12+): on app open and
     * after connecting a source. Cloud work waits for a network.
     *
     * By default a request is dropped while a one-shot sync of the same scope is already queued
     * or running (`KEEP`): that sync does the same work. With [replacePending] (time zone change)
     * the queued or running one is cancelled and a new one enqueued (`REPLACE`), because a sync
     * already past reading the device zone would store date-only items in the old zone.
     */
    fun requestSync(scope: SyncScope = SyncScope.ALL, replacePending: Boolean = false)
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun ensurePeriodic() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorkers.PERIODIC_LOCAL,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalSyncWorker>(SyncWorkers.PERIOD_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncWorkers.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            SyncWorkers.PERIODIC_CLOUD,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CloudSyncWorker>(SyncWorkers.PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(CONNECTED)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncWorkers.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun requestSync(scope: SyncScope, replacePending: Boolean) {
        val policy = if (replacePending) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        if (scope != SyncScope.CLOUD) {
            workManager.enqueueUniqueWork(SyncWorkers.NOW_LOCAL, policy, oneShot<LocalSyncWorker>(null))
        }
        if (scope != SyncScope.LOCAL) {
            workManager.enqueueUniqueWork(SyncWorkers.NOW_CLOUD, policy, oneShot<CloudSyncWorker>(CONNECTED))
        }
    }

    private inline fun <reified W : CoroutineWorker> oneShot(constraints: Constraints?): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>()
            .setInputData(workDataOf(SyncWorkers.KEY_FORCE to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncWorkers.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply {
                constraints?.let { setConstraints(it) }
                // Below Android 12 expedited work runs as a foreground service, which would need
                // getForegroundInfo(); there the app is in the foreground anyway when this is called.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()

    private companion object {
        val CONNECTED: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
