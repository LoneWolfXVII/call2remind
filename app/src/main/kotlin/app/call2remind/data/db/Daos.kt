package app.call2remind.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY type, id")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources ORDER BY type, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("UPDATE sources SET lastSyncAt = :at, syncCursor = :cursor WHERE id = :id")
    suspend fun markSynced(id: String, at: Instant, cursor: String?): Int

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface ReminderDao {
    @Upsert
    suspend fun upsertAll(items: List<ReminderEntity>)

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders ORDER BY title COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observe(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE sourceType = :sourceType AND externalId IN (:externalIds)")
    suspend fun findByExternalIds(sourceType: String, externalIds: List<String>): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM reminders WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String): Int
}

@Dao
interface OccurrenceDao {
    /** Inserts, ignoring rows whose id already exists. Returns -1 for ignored rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<OccurrenceEntity>): List<Long>

    @Update
    suspend fun update(item: OccurrenceEntity): Int

    @Query("SELECT * FROM occurrences WHERE id = :id")
    suspend fun get(id: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrences WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE id = :id")
    fun observe(id: String): Flow<OccurrenceEntity?>

    @Query("SELECT * FROM occurrences WHERE state IN ('SCHEDULED', 'RINGING', 'SNOOZED') ORDER BY fireAt, id")
    suspend fun getActive(): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE state IN ('SCHEDULED', 'RINGING', 'SNOOZED') ORDER BY fireAt, id")
    fun observeActive(): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM occurrences WHERE state IN ('SCHEDULED', 'SNOOZED') ORDER BY fireAt, id")
    suspend fun getPending(): List<OccurrenceEntity>

    /** Everything the planner needs for dedupe: all active rows plus any row planned since [since]. */
    @Query("SELECT * FROM occurrences WHERE state IN ('SCHEDULED', 'RINGING', 'SNOOZED') OR plannedAt >= :since")
    suspend fun getForPlanning(since: Instant): List<OccurrenceEntity>

    @Query("SELECT COUNT(*) FROM occurrences WHERE state = 'RINGING'")
    suspend fun countRinging(): Int

    /**
     * Compare-and-set: writes the new values only if the row still has the expected ones.
     * Returns the number of rows changed (0 or 1).
     */
    @Query(
        """
        UPDATE occurrences
        SET state = :newState, fireAt = :newFireAt, ringBacks = :newRingBacks, answeredAt = :newAnsweredAt
        WHERE id = :id
          AND state = :expectedState
          AND fireAt = :expectedFireAt
          AND ringBacks = :expectedRingBacks
          AND answeredAt IS :expectedAnsweredAt
        """,
    )
    suspend fun compareAndSet(
        id: String,
        newState: String,
        newFireAt: Instant,
        newRingBacks: Int,
        newAnsweredAt: Instant?,
        expectedState: String,
        expectedFireAt: Instant,
        expectedRingBacks: Int,
        expectedAnsweredAt: Instant?,
    ): Int

    @Query("DELETE FROM occurrences WHERE id IN (:ids) AND state IN ('SCHEDULED', 'SNOOZED')")
    suspend fun deletePending(ids: List<String>): Int

    @Query("DELETE FROM occurrences WHERE state IN ('DONE', 'MISSED', 'SKIPPED') AND fireAt < :before")
    suspend fun pruneTerminal(before: Instant): Int

    @Transaction
    @Query("SELECT * FROM occurrences WHERE state IN ('SCHEDULED', 'RINGING', 'SNOOZED') ORDER BY fireAt, id LIMIT :limit")
    fun observeActiveWithReminder(limit: Int): Flow<List<OccurrenceWithReminderRow>>

    @Transaction
    @Query("SELECT * FROM occurrences WHERE state IN ('DONE', 'MISSED', 'SKIPPED') ORDER BY fireAt DESC, id LIMIT :limit")
    fun observeHistoryWithReminder(limit: Int): Flow<List<OccurrenceWithReminderRow>>
}

@Dao
interface RingLogDao {
    @Insert
    suspend fun insert(entry: RingLogEntity): Long

    @Query("SELECT * FROM ring_log ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RingLogEntity>>

    @Query("SELECT * FROM ring_log WHERE occurrenceId = :occurrenceId ORDER BY timestamp, id")
    suspend fun getForOccurrence(occurrenceId: String): List<RingLogEntity>

    @Query("DELETE FROM ring_log WHERE timestamp < :before")
    suspend fun prune(before: Instant): Int
}
