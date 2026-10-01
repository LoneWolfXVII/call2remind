package app.call2remind.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.sync.Backoff
import app.call2remind.sources.RetryableSyncException
import app.call2remind.sources.SourceSnapshot
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.FakeNotificationAccess
import app.call2remind.testing.FakeReminderSource
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import app.call2remind.testing.awaitUntil
import app.call2remind.testing.hours
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SyncWorkersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val h = EngineHarness()
    private val calendar = FakeReminderSource(SourceType.CALENDAR)
    private val tasks = FakeReminderSource(SourceType.GOOGLE_TASKS, requiresNetwork = true)
    private val coordinator = SyncCoordinator(
        setOf(calendar, tasks), h.sources, h.reminders, h.engine, h.settings, FakeNotificationAccess(), h.clock, Backoff(),
    ) { UTC }

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            when (workerClassName) {
                LocalSyncWorker::class.java.name -> LocalSyncWorker(appContext, workerParameters, coordinator)
                CloudSyncWorker::class.java.name -> CloudSyncWorker(appContext, workerParameters, coordinator)
                else -> null
            }
    }

    @After
    fun tearDown() = h.close()

    private inline fun <reified W : CoroutineWorker> worker(attempt: Int = 0, force: Boolean = false): W =
        TestListenableWorkerBuilder<W>(app)
            .setWorkerFactory(factory)
            .setRunAttemptCount(attempt)
            .setInputData(workDataOf(SyncWorkers.KEY_FORCE to force))
            .build()

    @Test
    fun localWorkerSyncsDeviceSourcesOnly() = runBlocking<Unit> {
        calendar.next = { SourceSnapshot.Full(listOf(reminder("e", Schedule.At(T0.plus(hours(2))), sourceType = SourceType.CALENDAR))) }

        val result = worker<LocalSyncWorker>().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(calendar.requests).hasSize(1)
        assertThat(tasks.requests).isEmpty()
        assertThat(h.occurrences.getPending()).hasSize(1)
    }

    @Test
    fun cloudWorkerSyncsCloudSourcesOnly() = runBlocking<Unit> {
        val result = worker<CloudSyncWorker>().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(tasks.requests).hasSize(1)
        assertThat(calendar.requests).isEmpty()
    }

    @Test
    fun retryableSourceFailureRetriesTheWork() = runBlocking<Unit> {
        tasks.next = { throw RetryableSyncException("HTTP 503") }

        assertThat(worker<CloudSyncWorker>().doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun forcedWorkIgnoresBackoff() = runBlocking<Unit> {
        tasks.next = { throw IOException("offline") }
        worker<CloudSyncWorker>().doWork()
        tasks.next = { SourceSnapshot.Full(emptyList()) }

        // A forced run (one-shot on app open) ignores the per-source backoff.
        assertThat(worker<CloudSyncWorker>(force = true).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(tasks.requests.size).isAtLeast(2)
    }

    @Test
    fun givesUpRetryingAfterMaxAttempts() = runBlocking<Unit> {
        tasks.next = { throw RetryableSyncException("HTTP 503") }

        val result = worker<CloudSyncWorker>(attempt = SyncWorkers.MAX_ATTEMPTS, force = true).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun permanentSourceFailureDoesNotRetry() = runBlocking<Unit> {
        calendar.next = { throw IllegalStateException("bad row") }

        assertThat(worker<LocalSyncWorker>().doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun coordinatorCrashRetriesThenFails() = runBlocking<Unit> {
        h.settings.failure = IOException("disk")

        assertThat(worker<LocalSyncWorker>().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker<LocalSyncWorker>(attempt = SyncWorkers.MAX_ATTEMPTS).doWork()).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun schedulerEnqueuesUniquePeriodicSyncsWithNetworkOnlyForCloud() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        val scheduler = WorkManagerSyncScheduler(app)
        val workManager = WorkManager.getInstance(app)

        scheduler.ensurePeriodic()
        val local = workManager.getWorkInfosForUniqueWork(SyncWorkers.PERIODIC_LOCAL).get().single()
        val cloud = workManager.getWorkInfosForUniqueWork(SyncWorkers.PERIODIC_CLOUD).get().single()
        scheduler.ensurePeriodic()

        assertThat(workManager.getWorkInfosForUniqueWork(SyncWorkers.PERIODIC_LOCAL).get().single().id).isEqualTo(local.id)
        assertThat(workManager.getWorkInfosForUniqueWork(SyncWorkers.PERIODIC_CLOUD).get().single().id).isEqualTo(cloud.id)
        assertThat(local.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(cloud.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(local.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(cloud.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun requestSyncEnqueuesForcedOneShots() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        val scheduler = WorkManagerSyncScheduler(app)
        val workManager = WorkManager.getInstance(app)

        scheduler.requestSync(SyncScope.LOCAL)

        // The local one-shot has no constraints, so it runs right away (forced).
        awaitUntil(message = "local one-shot finished") {
            workManager.getWorkInfosForUniqueWork(SyncWorkers.NOW_LOCAL).get().singleOrNull()?.state == WorkInfo.State.SUCCEEDED
        }
        assertThat(calendar.requests).hasSize(1)
        assertThat(workManager.getWorkInfosForUniqueWork(SyncWorkers.NOW_CLOUD).get()).isEmpty()

        scheduler.requestSync(SyncScope.CLOUD)
        val cloud = workManager.getWorkInfosForUniqueWork(SyncWorkers.NOW_CLOUD).get().single()
        assertThat(cloud.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun aReplacingRequestSupersedesAQueuedSyncWhileAPlainOneIsDropped() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        val scheduler = WorkManagerSyncScheduler(app)
        val workManager = WorkManager.getInstance(app)
        fun cloud() = workManager.getWorkInfosForUniqueWork(SyncWorkers.NOW_CLOUD).get().filter { it.state != WorkInfo.State.CANCELLED }

        scheduler.requestSync(SyncScope.CLOUD)
        val first = cloud().single()
        // Waiting for a network (test constraints are unmet), i.e. still queued.
        assertThat(first.state).isEqualTo(WorkInfo.State.ENQUEUED)

        scheduler.requestSync(SyncScope.CLOUD)
        assertThat(cloud().single().id).isEqualTo(first.id)

        // A time zone change must not be swallowed by the queued (or running) old-zone sync.
        scheduler.requestSync(SyncScope.CLOUD, replacePending = true)
        val replacement = cloud().single()
        assertThat(replacement.id).isNotEqualTo(first.id)
        assertThat(workManager.getWorkInfoById(first.id).get()?.state ?: WorkInfo.State.CANCELLED).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(replacement.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
