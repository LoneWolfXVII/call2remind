package app.call2remind.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.model.Schedule
import app.call2remind.testing.EngineHarness
import app.call2remind.testing.T0
import app.call2remind.testing.hours
import app.call2remind.testing.minutes
import app.call2remind.testing.occurrence
import app.call2remind.testing.reminder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WorkersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val h = EngineHarness()

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            when (workerClassName) {
                WatchdogWorker::class.java.name -> WatchdogWorker(appContext, workerParameters, h.engine)
                DailyReplanWorker::class.java.name -> DailyReplanWorker(appContext, workerParameters, h.engine)
                else -> null
            }
    }

    @After
    fun tearDown() = h.close()

    private inline fun <reified W : CoroutineWorker> worker(): W =
        TestListenableWorkerBuilder<W>(app).setWorkerFactory(factory).build()

    @Test
    fun watchdogRecoversOverdueRingsAndReArms() = runBlocking<Unit> {
        val late = reminder("late", Schedule.At(T0.plus(hours(4))))
        val ancient = reminder("ancient", Schedule.At(T0.plus(hours(2))))
        h.engine.upsertReminders(listOf(late, ancient))
        h.clock.now = T0.plus(hours(4)).plus(minutes(30))
        h.alarms.reset()

        val result = worker<WatchdogWorker>().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(h.occurrences.get(Occurrence.scheduled(ancient, T0.plus(hours(2))).id)?.state).isEqualTo(OccurrenceState.MISSED)
        val lateId = Occurrence.scheduled(late, T0.plus(hours(4))).id
        assertThat(h.occurrences.get(lateId)?.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(h.alarms.armed[lateId]?.at).isEqualTo(h.clock.now)
    }

    @Test
    fun watchdogRetriesOnFailure() = runBlocking<Unit> {
        h.settings.failure = IOException("disk")

        assertThat(worker<WatchdogWorker>().doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun dailyReplanTopsUpTheWindowAndPrunesHistory() = runBlocking<Unit> {
        val daily = reminder("d", Schedule.At(T0.plus(hours(30))))
        h.reminders.upsert(daily)
        val old = occurrence(daily, T0.minus(Duration.ofDays(45)), state = OccurrenceState.DONE)
        h.occurrences.insertIfAbsent(old)

        val result = worker<DailyReplanWorker>().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(h.occurrences.getPending().map { it.fireAt }).containsExactly(T0.plus(hours(30)))
        assertThat(h.occurrences.get(old.id)).isNull()
    }

    @Test
    fun dailyReplanRetriesOnFailure() = runBlocking<Unit> {
        h.settings.failure = IllegalStateException("boom")

        assertThat(worker<DailyReplanWorker>().doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun backgroundJobsAreUniquePeriodicAndKept() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        val jobs = WorkManagerBackgroundJobs(app)
        val workManager = WorkManager.getInstance(app)

        jobs.ensureScheduled()
        val watchdog = workManager.getWorkInfosForUniqueWork(WatchdogWorker.UNIQUE_NAME).get().single()
        val daily = workManager.getWorkInfosForUniqueWork(DailyReplanWorker.UNIQUE_NAME).get().single()
        jobs.ensureScheduled()

        assertThat(workManager.getWorkInfosForUniqueWork(WatchdogWorker.UNIQUE_NAME).get().single().id).isEqualTo(watchdog.id)
        assertThat(workManager.getWorkInfosForUniqueWork(DailyReplanWorker.UNIQUE_NAME).get().single().id).isEqualTo(daily.id)
        // The test WorkManager may already be running the first period (no constraints/delay).
        assertThat(watchdog.state).isAnyOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)
        assertThat(watchdog.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(daily.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.DAYS.toMillis(1))
    }
}
