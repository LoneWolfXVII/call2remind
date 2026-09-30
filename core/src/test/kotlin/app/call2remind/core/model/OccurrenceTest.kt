package app.call2remind.core.model

import app.call2remind.core.Fixtures
import app.call2remind.core.Fixtures.instant
import app.call2remind.core.planning.RequestCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OccurrenceTest {

    @Test
    fun keyIdIsDeterministicWithExternalIdLast() {
        val key = OccurrenceKey(SourceType.CALENDAR, "evt|1", instant("2026-01-01T00:00:00Z"))

        assertThat(key.id).isEqualTo("CALENDAR|1767225600000|evt|1")
    }

    @Test
    fun keysDifferBySourceExternalIdAndInstant() {
        val t = instant("2026-01-01T00:00:00Z")
        val ids = setOf(
            OccurrenceKey(SourceType.CALENDAR, "a", t).id,
            OccurrenceKey(SourceType.GOOGLE_TASKS, "a", t).id,
            OccurrenceKey(SourceType.CALENDAR, "b", t).id,
            OccurrenceKey(SourceType.CALENDAR, "a", t.plusMillis(1)).id,
        )

        assertThat(ids).hasSize(4)
    }

    @Test
    fun scheduledFactoryFillsIdentityAndRequestCode() {
        val t = instant("2026-01-01T03:30:00Z")
        val reminder = Fixtures.reminder(
            id = "r9",
            schedule = Schedule.At(t),
            sourceType = SourceType.MS_TODO,
            externalId = "task-9",
        )

        val occurrence = Occurrence.scheduled(reminder, t)

        assertThat(occurrence.id).isEqualTo(OccurrenceKey(SourceType.MS_TODO, "task-9", t).id)
        assertThat(occurrence.reminderId).isEqualTo("r9")
        assertThat(occurrence.sourceType).isEqualTo(SourceType.MS_TODO)
        assertThat(occurrence.plannedAt).isEqualTo(t)
        assertThat(occurrence.fireAt).isEqualTo(t)
        assertThat(occurrence.state).isEqualTo(OccurrenceState.SCHEDULED)
        assertThat(occurrence.ringBacks).isEqualTo(0)
        assertThat(occurrence.answeredAt).isNull()
        assertThat(occurrence.requestCode).isEqualTo(RequestCodes.forOccurrence(occurrence.id))
    }

    @Test
    fun stateFlags() {
        assertThat(OccurrenceState.entries.filter { it.isActive })
            .containsExactly(OccurrenceState.SCHEDULED, OccurrenceState.RINGING, OccurrenceState.SNOOZED)
        assertThat(OccurrenceState.entries.filter { it.isTerminal })
            .containsExactly(OccurrenceState.DONE, OccurrenceState.MISSED, OccurrenceState.SKIPPED)
    }
}
