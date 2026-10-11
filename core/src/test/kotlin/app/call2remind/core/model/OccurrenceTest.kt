package app.call2remind.core.model

import app.call2remind.core.Fixtures
import app.call2remind.core.Fixtures.instant
import app.call2remind.core.planning.RequestCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

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
    fun subMillisecondPlannedInstantsAreTruncatedAtTheSource() {
        val exact = instant("2026-01-01T03:30:00Z").plusNanos(123_456)
        val millis = instant("2026-01-01T03:30:00Z")
        val reminder = Fixtures.reminder(schedule = Schedule.At(exact))

        val occurrence = Occurrence.scheduled(reminder, exact)

        assertThat(occurrence.plannedAt).isEqualTo(millis)
        assertThat(occurrence.fireAt).isEqualTo(millis)
        assertThat(OccurrenceKey.of(reminder, exact)).isEqualTo(OccurrenceKey.of(reminder, millis))
        // What a Room (millisecond) round trip returns equals what planning produces.
        assertThat(occurrence.copy(plannedAt = Instant.ofEpochMilli(exact.toEpochMilli()))).isEqualTo(occurrence)
    }

    @Test
    fun keyParsesBackFromId() {
        val key = OccurrenceKey(SourceType.CALENDAR, "evt|1|x", instant("2026-01-01T00:00:00Z"))

        assertThat(OccurrenceKey.parse(key.id)).isEqualTo(key)
        assertThat(OccurrenceKey.parse("CALENDAR|1767225600000|")).isEqualTo(
            OccurrenceKey(SourceType.CALENDAR, "", instant("2026-01-01T00:00:00Z")),
        )
        listOf("", "o1", "CALENDAR|x|e", "NOPE|1|e", "CALENDAR|1").forEach {
            assertThat(OccurrenceKey.parse(it)).isNull()
        }
    }

    @Test
    fun stateFlags() {
        assertThat(OccurrenceState.entries.filter { it.isActive })
            .containsExactly(OccurrenceState.SCHEDULED, OccurrenceState.RINGING, OccurrenceState.SNOOZED)
        assertThat(OccurrenceState.entries.filter { it.isTerminal })
            .containsExactly(OccurrenceState.DONE, OccurrenceState.MISSED, OccurrenceState.SKIPPED)
    }
}
