package app.call2remind.scheduling

import app.call2remind.core.model.Occurrence
import java.time.Instant

/**
 * Arms and cancels the OS alarm of an occurrence. One alarm per occurrence, identified by the
 * occurrence id (and its stable request code); arming again replaces the previous alarm, so all
 * calls are idempotent.
 */
interface AlarmScheduler {
    /**
     * Arms the alarm of [occurrence] to fire at [triggerAt].
     *
     * @param isSoonest true for the earliest armed occurrence; the Android implementation uses
     * `setAlarmClock` for it (highest reliability, shown as the next alarm) and
     * `setExactAndAllowWhileIdle` for the rest.
     */
    fun arm(occurrence: Occurrence, triggerAt: Instant, isSoonest: Boolean)

    /** Cancels the alarm of [occurrence] if armed; no-op otherwise. */
    fun cancel(occurrence: Occurrence)
}
