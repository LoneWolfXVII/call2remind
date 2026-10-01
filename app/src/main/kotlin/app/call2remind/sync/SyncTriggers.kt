package app.call2remind.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.CalendarContract
import android.util.Log
import app.call2remind.core.model.SourceType
import app.call2remind.di.ApplicationScope
import app.call2remind.receivers.runAsync
import app.call2remind.sources.calendar.CalendarReader
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects items and runs [action] once with all of them after [quietPeriod] without new
 * submissions. A run that already started is never cancelled by later submissions (they start a
 * new period). Thread-safe.
 */
class Debouncer<T>(
    private val scope: CoroutineScope,
    private val quietPeriod: Duration,
    private val action: suspend (Set<T>) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashSet<T>()
    private var job: Job? = null

    fun submit(item: T) {
        synchronized(lock) {
            pending += item
            job?.cancel()
            job = scope.launch {
                delay(quietPeriod.toMillis())
                val self = coroutineContext.job
                val items = synchronized(lock) {
                    if (job !== self) return@launch
                    job = null
                    pending.toSet().also { pending.clear() }
                }
                action(items)
            }
        }
    }
}

/**
 * Keeps calendar reminders fresh while the process is alive: a `ContentObserver` on the whole
 * calendar provider triggers a forced calendar sync, debounced by [DEBOUNCE] (sync adapters
 * write in bursts). Registration needs `READ_CALENDAR`, so [ensureRegistered] is idempotent and
 * called again on app open (after onboarding grants the permission).
 */
@Singleton
class CalendarChangeObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: CalendarReader,
    private val coordinator: SyncCoordinator,
    @ApplicationScope scope: CoroutineScope,
) {
    private val debouncer = Debouncer<SourceType>(scope, DEBOUNCE) { types -> coordinator.sync(types, force = true) }

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = onCalendarChanged()
    }

    @Volatile
    private var registered = false

    /** Registers the observer if possible; returns whether it is registered. */
    @Synchronized
    fun ensureRegistered(): Boolean {
        if (registered) return true
        if (!reader.hasPermission()) return false
        try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            registered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot observe the calendar provider", e)
        }
        return registered
    }

    /** Schedules a debounced calendar sync. */
    fun onCalendarChanged() = debouncer.submit(SourceType.CALENDAR)

    companion object {
        private const val TAG = "CalendarChangeObserver"
        val DEBOUNCE: Duration = Duration.ofSeconds(2)
    }
}

/**
 * Backup trigger: the calendar provider broadcasts `EVENT_REMINDER` when an event's own reminder
 * fires; if our copy is stale (e.g. the observer wasn't registered), a calendar sync picks up the
 * change. Exempt from implicit-broadcast limits.
 */
@AndroidEntryPoint
class EventReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: SyncCoordinator

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER) return
        runAsync(scope) { coordinator.sync(setOf(SourceType.CALENDAR), force = true) }
    }
}
