package app.call2remind.sources.habit

import app.call2remind.core.model.LeadOffset
import app.call2remind.core.model.Reminder
import app.call2remind.core.model.Schedule
import app.call2remind.core.model.SourceType
import app.call2remind.core.recurrence.RecurrenceRule
import app.call2remind.data.model.ReminderIds
import app.call2remind.data.model.ReminderSource
import app.call2remind.data.repo.ReminderRepository
import app.call2remind.data.repo.SourceRepository
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.sources.SourceIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An in-app habit: a weekly [RecurrenceRule] (days of week + times, every N weeks).
 *
 * @property id the habit's own id; it is the reminder's `externalId`.
 */
data class Habit(
    val id: String,
    val title: String,
    val rule: RecurrenceRule,
    val zone: ZoneId,
    val notes: String? = null,
    val lead: LeadOffset = LeadOffset.NONE,
    val enabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ttsEnabled: Boolean = true,
) {
    /** Local reminder id. */
    val reminderId: String get() = ReminderIds.of(SourceType.HABIT, id)

    fun toReminder(): Reminder = Reminder(
        id = reminderId,
        sourceType = SourceType.HABIT,
        externalId = id,
        title = title,
        schedule = Schedule.Recurring(rule, lead),
        zone = zone,
        notes = notes,
        ringtoneUri = ringtoneUri,
        ttsEnabled = ttsEnabled,
        enabled = enabled,
    )

    companion object {
        /** The habit stored as [reminder], or `null` if it is not a habit. */
        fun fromReminder(reminder: Reminder): Habit? {
            if (reminder.sourceType != SourceType.HABIT) return null
            val schedule = reminder.schedule as? Schedule.Recurring ?: return null
            return Habit(
                id = reminder.externalId,
                title = reminder.title,
                rule = schedule.rule,
                zone = reminder.zone,
                notes = reminder.notes,
                lead = schedule.lead,
                enabled = reminder.enabled,
                ringtoneUri = reminder.ringtoneUri,
                ttsEnabled = reminder.ttsEnabled,
            )
        }
    }
}

/**
 * CRUD for habits (New habit screen). Every write replans, so alarms follow immediately.
 *
 * Storage decision: habits are **not** a separate table. They are ordinary `reminders` rows with
 * `sourceType = HABIT`, `sourceId = "habits"`, `externalId = habit id` and a
 * `Schedule.Recurring` schedule. That keeps one source of truth for the planner, needs no Room
 * migration, and a habit's history/occurrences behave exactly like any other reminder's.
 * Habits are never "synced": the sync coordinator does not touch them.
 */
interface HabitRepository {
    fun observeAll(): Flow<List<Habit>>

    suspend fun get(id: String): Habit?

    /** Creates a habit in the device's zone and returns it. */
    suspend fun create(
        title: String,
        rule: RecurrenceRule,
        notes: String? = null,
        lead: LeadOffset = LeadOffset.NONE,
        ringtoneUri: String? = null,
        ttsEnabled: Boolean = true,
    ): Habit

    /** Replaces the stored habit with the same [Habit.id] (creates it if missing). */
    suspend fun update(habit: Habit)

    /** Pauses / resumes a habit. Returns false if it does not exist. */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean

    suspend fun delete(id: String)
}

@Singleton
class ReminderBackedHabitRepository(
    private val reminders: ReminderRepository,
    private val sources: SourceRepository,
    private val engine: SchedulingEngine,
    private val clock: Clock,
    private val newId: () -> String,
) : HabitRepository {
    @Inject
    constructor(
        reminders: ReminderRepository,
        sources: SourceRepository,
        engine: SchedulingEngine,
        clock: Clock,
    ) : this(reminders, sources, engine, clock, { UUID.randomUUID().toString() })

    override fun observeAll(): Flow<List<Habit>> =
        reminders.observeAll().map { all -> all.mapNotNull { Habit.fromReminder(it) } }

    override suspend fun get(id: String): Habit? =
        reminders.get(ReminderIds.of(SourceType.HABIT, id))?.let { Habit.fromReminder(it) }

    override suspend fun create(
        title: String,
        rule: RecurrenceRule,
        notes: String?,
        lead: LeadOffset,
        ringtoneUri: String?,
        ttsEnabled: Boolean,
    ): Habit {
        val habit = Habit(
            id = newId(),
            title = title.trim(),
            rule = rule,
            zone = clock.zone,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            lead = lead,
            ringtoneUri = ringtoneUri,
            ttsEnabled = ttsEnabled,
        )
        update(habit)
        return habit
    }

    override suspend fun update(habit: Habit) {
        ensureSourceRow()
        engine.upsertReminders(listOf(habit.toReminder()), SourceIds.HABITS)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        val habit = get(id) ?: return false
        if (habit.enabled != enabled) update(habit.copy(enabled = enabled))
        return true
    }

    override suspend fun delete(id: String) {
        engine.deleteReminders(listOf(ReminderIds.of(SourceType.HABIT, id)))
    }

    private suspend fun ensureSourceRow() {
        if (sources.get(SourceIds.HABITS) == null) {
            sources.upsert(ReminderSource(id = SourceIds.HABITS, type = SourceType.HABIT, displayName = "Habits"))
        }
    }
}
