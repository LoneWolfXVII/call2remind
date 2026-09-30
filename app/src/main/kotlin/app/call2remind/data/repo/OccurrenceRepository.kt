package app.call2remind.data.repo

import androidx.room.withTransaction
import app.call2remind.core.log.RingLogEvent
import app.call2remind.core.model.Occurrence
import app.call2remind.core.model.OccurrenceState
import app.call2remind.core.planning.Plan
import app.call2remind.core.ringing.OccurrenceEvent
import app.call2remind.core.ringing.OccurrenceStateMachine
import app.call2remind.core.ringing.TransitionResult
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.mapper.toEntity
import app.call2remind.data.mapper.toModel
import app.call2remind.data.model.OccurrenceWithReminder
import app.call2remind.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Occurrences and the ring log. Every state change goes through [transition] or [tryClaimRing],
 * each of which writes the new state and its ring-log entry in ONE Room transaction.
 */
interface OccurrenceRepository {
    fun observe(id: String): Flow<Occurrence?>

    /** Active (scheduled / ringing / snoozed) occurrences with their reminders, soonest first. */
    fun observeUpcoming(limit: Int = DEFAULT_LIMIT): Flow<List<OccurrenceWithReminder>>

    /** Finished (done / missed / skipped) occurrences, newest first. */
    fun observeHistory(limit: Int = DEFAULT_LIMIT): Flow<List<OccurrenceWithReminder>>

    /** Ring log, newest first. */
    fun observeRingLog(limit: Int = DEFAULT_LIMIT): Flow<List<RingLogEvent>>

    suspend fun get(id: String): Occurrence?

    /** SCHEDULED, RINGING and SNOOZED occurrences ordered by fireAt. */
    suspend fun getActive(): List<Occurrence>

    /** SCHEDULED and SNOOZED occurrences (those that need an alarm) ordered by fireAt. */
    suspend fun getPending(): List<Occurrence>

    /** All active occurrences plus every occurrence planned at or after [since] (planner input). */
    suspend fun getForPlanning(since: Instant): List<Occurrence>

    suspend fun getRingLog(occurrenceId: String): List<RingLogEvent>

    /**
     * Applies a planner [Plan] in one transaction: inserts `toCreate` (ignoring ids that already
     * exist) and DELETES `toCancel` rows that are still SCHEDULED/SNOOZED.
     */
    suspend fun applyPlan(plan: Plan): AppliedPlan

    /** Inserts [occurrence] unless its id exists. Returns true if inserted. */
    suspend fun insertIfAbsent(occurrence: Occurrence): Boolean

    /**
     * Loads the occurrence, applies [OccurrenceStateMachine] with the current snooze policy and
     * writes the new state plus the ring-log entry atomically. Returns `null` if [id] is unknown;
     * an [TransitionResult.InvalidTransition] changes nothing.
     */
    suspend fun transition(id: String, event: OccurrenceEvent): TransitionResult?

    /**
     * Exactly-once ring lock shared by the alarm, the watchdog and the Samsung listener.
     * Atomically moves the occurrence SCHEDULED/SNOOZED → RINGING (the state machine's `Fire`:
     * fireAt = [now], logs FIRED) only if it is due (`fireAt <= now`) and no other occurrence is
     * RINGING (one line). Returns true for exactly one caller.
     */
    suspend fun tryClaimRing(id: String, now: Instant): Boolean

    /** Appends a log entry that is not tied to a state change (e.g. DEFERRED, FAILED). */
    suspend fun appendLog(event: RingLogEvent)

    /** Deletes terminal occurrences and log entries older than [before]. */
    suspend fun pruneHistory(before: Instant): Int

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}

/** What [OccurrenceRepository.applyPlan] actually changed. */
data class AppliedPlan(val created: List<Occurrence>, val deleted: List<Occurrence>)

@Singleton
class RoomOccurrenceRepository @Inject constructor(
    private val db: Call2RemindDb,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : OccurrenceRepository {
    private val dao = db.occurrenceDao()
    private val logDao = db.ringLogDao()

    override fun observe(id: String): Flow<Occurrence?> = dao.observe(id).map { it?.toModel() }

    override fun observeUpcoming(limit: Int): Flow<List<OccurrenceWithReminder>> =
        dao.observeActiveWithReminder(limit).map { rows -> rows.map { it.toModel() } }

    override fun observeHistory(limit: Int): Flow<List<OccurrenceWithReminder>> =
        dao.observeHistoryWithReminder(limit).map { rows -> rows.map { it.toModel() } }

    override fun observeRingLog(limit: Int): Flow<List<RingLogEvent>> =
        logDao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun get(id: String): Occurrence? = dao.get(id)?.toModel()

    override suspend fun getActive(): List<Occurrence> = dao.getActive().map { it.toModel() }

    override suspend fun getPending(): List<Occurrence> = dao.getPending().map { it.toModel() }

    override suspend fun getForPlanning(since: Instant): List<Occurrence> = dao.getForPlanning(since).map { it.toModel() }

    override suspend fun getRingLog(occurrenceId: String): List<RingLogEvent> =
        logDao.getForOccurrence(occurrenceId).map { it.toModel() }

    override suspend fun applyPlan(plan: Plan): AppliedPlan = db.withTransaction {
        val cancelIds = plan.toCancel.map { it.occurrence.id }
        val deleted = cancelIds.chunked(MAX_SQL_ARGS)
            .flatMap { dao.getByIds(it) }
            .filter { it.state == OccurrenceState.SCHEDULED || it.state == OccurrenceState.SNOOZED }
        deleted.map { it.id }.chunked(MAX_SQL_ARGS).forEach { dao.deletePending(it) }

        val created = if (plan.toCreate.isEmpty()) {
            emptyList()
        } else {
            val rowIds = dao.insertAll(plan.toCreate.map { it.toEntity() })
            plan.toCreate.filterIndexed { index, _ -> rowIds.getOrNull(index) != IGNORED_ROW }
        }
        AppliedPlan(created = created, deleted = deleted.map { it.toModel() })
    }

    override suspend fun insertIfAbsent(occurrence: Occurrence): Boolean =
        dao.insertAll(listOf(occurrence.toEntity())).firstOrNull() != IGNORED_ROW

    override suspend fun transition(id: String, event: OccurrenceEvent): TransitionResult? {
        val machine = OccurrenceStateMachine(clock, settings.current().snoozePolicy)
        return db.withTransaction {
            val current = dao.get(id)?.toModel() ?: return@withTransaction null
            val result = machine.transition(current, event)
            if (result is TransitionResult.Transitioned) {
                val written = writeIfUnchanged(current, result.occurrence)
                check(written) { "Occurrence $id changed inside its transaction" }
                logDao.insert(result.logEvent.toEntity())
            }
            result
        }
    }

    override suspend fun tryClaimRing(id: String, now: Instant): Boolean = db.withTransaction {
        val current = dao.get(id)?.toModel() ?: return@withTransaction false
        val pending = current.state == OccurrenceState.SCHEDULED || current.state == OccurrenceState.SNOOZED
        if (!pending || current.fireAt.isAfter(now)) return@withTransaction false
        if (dao.countRinging() > 0) return@withTransaction false
        val machine = OccurrenceStateMachine(Clock.fixed(now, ZoneOffset.UTC))
        val result = machine.transition(current, OccurrenceEvent.Fire) as? TransitionResult.Transitioned
            ?: return@withTransaction false
        if (!writeIfUnchanged(current, result.occurrence)) return@withTransaction false
        logDao.insert(result.logEvent.toEntity())
        true
    }

    override suspend fun appendLog(event: RingLogEvent) {
        logDao.insert(event.toEntity())
    }

    override suspend fun pruneHistory(before: Instant): Int = db.withTransaction {
        dao.pruneTerminal(before) + logDao.prune(before)
    }

    /** Compare-and-set of the mutable fields; true if the row still matched [expected]. */
    private suspend fun writeIfUnchanged(expected: Occurrence, updated: Occurrence): Boolean =
        dao.compareAndSet(
            id = expected.id,
            newState = updated.state.name,
            newFireAt = updated.fireAt,
            newRingBacks = updated.ringBacks,
            newAnsweredAt = updated.answeredAt,
            expectedState = expected.state.name,
            expectedFireAt = expected.fireAt,
            expectedRingBacks = expected.ringBacks,
            expectedAnsweredAt = expected.answeredAt,
        ) == 1

    private companion object {
        const val MAX_SQL_ARGS = 500
        const val IGNORED_ROW = -1L
    }
}
