package app.call2remind.scheduling

import app.call2remind.core.model.Occurrence

/** Tells the user about missed / auto-snoozed rings. */
interface MissedNotifier {
    /** [occurrence] was missed (MISSED) or auto-snoozed after ringing unanswered (SNOOZED). */
    suspend fun onMissed(occurrence: Occurrence)

    /** [occurrence] was handled (done / skipped): dismiss any missed notice for it. */
    suspend fun onResolved(occurrence: Occurrence)

    /**
     * [occurrence] stopped ringing (any transition out of RINGING): dismiss a fallback ringing
     * notification posted when the ringing service could not start. No-op by default.
     */
    suspend fun onRingEnded(occurrence: Occurrence) = Unit
}
