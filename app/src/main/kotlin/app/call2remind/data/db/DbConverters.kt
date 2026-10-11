package app.call2remind.data.db

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room converters.
 *
 * Instants are stored **losslessly as epoch nanoseconds** (a `Long` covers years 1677–2262,
 * plenty for a 48 h alarm window and ring history). Millisecond storage would truncate
 * sub-millisecond instants (e.g. a "ring me in 1 minute" `Schedule.At(clock.instant() + 1 min)`),
 * after which the planner would no longer recognise the stored `plannedAt` as produced by the
 * reminder's schedule and would cancel/recreate it on every replan.
 */
class DbConverters {
    @TypeConverter
    fun instantToEpochNanos(value: Instant): Long = EpochNanos.fromInstant(value)

    @TypeConverter
    fun epochNanosToInstant(value: Long): Instant = EpochNanos.toInstant(value)
}

/** Lossless `Instant` <-> epoch-nanosecond conversion used by the database. */
object EpochNanos {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** Epoch nanoseconds of [instant]; throws [ArithmeticException] outside 1677–2262. */
    fun fromInstant(instant: Instant): Long =
        Math.addExact(Math.multiplyExact(instant.epochSecond, NANOS_PER_SECOND), instant.nano.toLong())

    /** Inverse of [fromInstant]. */
    fun toInstant(epochNanos: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(epochNanos, NANOS_PER_SECOND), Math.floorMod(epochNanos, NANOS_PER_SECOND))
}
