package app.call2remind.data.repo

import androidx.room.withTransaction
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.SourceType
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.mapper.toEntity
import app.call2remind.data.mapper.toModel
import app.call2remind.data.mapper.toModelOrNull
import app.call2remind.data.model.ReminderSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminders, normalized from every source.
 *
 * Writing reminders does NOT plan occurrences by itself: call
 * [app.call2remind.scheduling.SchedulingEngine.replan] afterwards, or use
 * [app.call2remind.scheduling.SchedulingEngine.applySourceSnapshot] which does both.
 */
interface ReminderRepository {
    fun observeAll(): Flow<List<Reminder>>
    fun observe(id: String): Flow<Reminder?>
    suspend fun getAll(): List<Reminder>
    suspend fun get(id: String): Reminder?

    /**
     * Readable reminders plus the ids of stored rows that could not be read (corrupt schedule
     * or zone). The planner must not mistake an unreadable reminder for a removed one.
     */
    suspend fun getAllForPlanning(): ReminderSnapshot = ReminderSnapshot(getAll(), emptySet())

    /**
     * Inserts or updates [reminders], attributed to [sourceId]. If a stored reminder already has
     * the same `(sourceType, externalId)` under a different id, the stored id is kept (the
     * returned list carries the ids actually used).
     */
    suspend fun upsertAll(reminders: List<Reminder>, sourceId: String? = null): List<Reminder>

    suspend fun upsert(reminder: Reminder, sourceId: String? = null): Reminder =
        upsertAll(listOf(reminder), sourceId).first()

    /**
     * Makes [reminders] the complete set for [sourceId]: upserts them and deletes the source's
     * reminders that are not in the list. Used by full syncs.
     */
    suspend fun replaceForSource(sourceId: String, reminders: List<Reminder>): SourceReplaceResult

    suspend fun delete(ids: Collection<String>): Int

    /**
     * Moves reminders [ids] to [zone] (only the zone changes: source, timestamps and the rest are
     * kept). Returns the number of rows updated. See [app.call2remind.core.time.DeviceZonePolicy].
     */
    suspend fun setZone(ids: Collection<String>, zone: ZoneId): Int
}

/** See [ReminderRepository.getAllForPlanning]. */
data class ReminderSnapshot(val reminders: List<Reminder>, val unreadableIds: Set<String>)

/** Outcome of [ReminderRepository.replaceForSource]. */
data class SourceReplaceResult(val upserted: List<Reminder>, val deletedIds: List<String>)

/** Configured sources (accounts). */
interface SourceRepository {
    fun observeAll(): Flow<List<ReminderSource>>
    suspend fun getAll(): List<ReminderSource>
    suspend fun get(id: String): ReminderSource?
    suspend fun upsert(source: ReminderSource)
    suspend fun markSynced(id: String, at: Instant, cursor: String?)

    /** Deletes the source and all its reminders (replan afterwards to drop their alarms). */
    suspend fun delete(id: String)
}

@Singleton
class RoomReminderRepository @Inject constructor(
    private val db: Call2RemindDb,
    private val clock: Clock,
) : ReminderRepository {
    private val dao = db.reminderDao()

    override fun observeAll(): Flow<List<Reminder>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModelOrNull() } }

    override fun observe(id: String): Flow<Reminder?> = dao.observe(id).map { it?.toModelOrNull() }

    override suspend fun getAll(): List<Reminder> = dao.getAll().mapNotNull { it.toModelOrNull() }

    override suspend fun get(id: String): Reminder? = dao.get(id)?.toModelOrNull()

    override suspend fun getAllForPlanning(): ReminderSnapshot {
        val rows = dao.getAll()
        val readable = ArrayList<Reminder>(rows.size)
        val unreadable = HashSet<String>()
        for (row in rows) {
            val reminder = row.toModelOrNull()
            if (reminder == null) unreadable += row.id else readable += reminder
        }
        return ReminderSnapshot(readable, unreadable)
    }

    override suspend fun upsertAll(reminders: List<Reminder>, sourceId: String?): List<Reminder> =
        db.withTransaction {
            val resolved = resolveIds(reminders)
            val now = clock.instant()
            dao.upsertAll(resolved.map { it.toEntity(sourceId, now) })
            resolved
        }

    override suspend fun replaceForSource(sourceId: String, reminders: List<Reminder>): SourceReplaceResult =
        db.withTransaction {
            val resolved = resolveIds(reminders)
            val now = clock.instant()
            dao.upsertAll(resolved.map { it.toEntity(sourceId, now) })
            val keep = resolved.mapTo(HashSet()) { it.id }
            val stale = dao.getBySource(sourceId).map { it.id }.filterNot { it in keep }
            stale.chunked(MAX_SQL_ARGS).forEach { dao.deleteByIds(it) }
            SourceReplaceResult(resolved, stale)
        }

    override suspend fun delete(ids: Collection<String>): Int =
        db.withTransaction { ids.toList().chunked(MAX_SQL_ARGS).sumOf { dao.deleteByIds(it) } }

    override suspend fun setZone(ids: Collection<String>, zone: ZoneId): Int =
        db.withTransaction { ids.toList().chunked(MAX_SQL_ARGS).sumOf { dao.setZone(it, zone.id) } }

    /** De-duplicates by `(sourceType, externalId)` (last wins) and reuses stored ids. */
    private suspend fun resolveIds(reminders: List<Reminder>): List<Reminder> {
        val unique = LinkedHashMap<Pair<SourceType, String>, Reminder>()
        for (reminder in reminders) unique[reminder.sourceType to reminder.externalId] = reminder
        val result = ArrayList<Reminder>(unique.size)
        for ((type, group) in unique.values.groupBy { it.sourceType }) {
            val stored = group.map { it.externalId }.chunked(MAX_SQL_ARGS)
                .flatMap { dao.findByExternalIds(type.name, it) }
                .associate { it.externalId to it.id }
            group.mapTo(result) { r -> stored[r.externalId]?.takeIf { it != r.id }?.let { r.copy(id = it) } ?: r }
        }
        return result
    }

    private companion object {
        const val MAX_SQL_ARGS = 500
    }
}

@Singleton
class RoomSourceRepository @Inject constructor(private val db: Call2RemindDb) : SourceRepository {
    private val dao = db.sourceDao()

    override fun observeAll(): Flow<List<ReminderSource>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun getAll(): List<ReminderSource> = dao.getAll().map { it.toModel() }

    override suspend fun get(id: String): ReminderSource? = dao.get(id)?.toModel()

    override suspend fun upsert(source: ReminderSource) = dao.upsert(source.toEntity())

    override suspend fun markSynced(id: String, at: Instant, cursor: String?) {
        dao.markSynced(id, at, cursor)
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            db.reminderDao().deleteBySource(id)
            dao.delete(id)
        }
    }
}
