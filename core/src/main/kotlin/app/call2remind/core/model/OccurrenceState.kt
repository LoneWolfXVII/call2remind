package app.call2remind.core.model

/** Lifecycle state of an [Occurrence]. See [app.call2remind.core.ringing.OccurrenceStateMachine]. */
enum class OccurrenceState {
    /** Alarm armed, waiting for its first ring. */
    SCHEDULED,

    /** Currently ringing (or answered and being handled). Holds the single "line". */
    RINGING,

    /** Declined/snoozed/timed out; will ring again at [Occurrence.fireAt]. */
    SNOOZED,

    /** Handled by the user. Terminal. */
    DONE,

    /** Never handled (too late or too many ring-backs). Terminal, but can still be marked done. */
    MISSED,

    /** Deliberately skipped by the user. Terminal. */
    SKIPPED,
    ;

    /** True for states that still need an alarm or are ringing. */
    val isActive: Boolean get() = this == SCHEDULED || this == RINGING || this == SNOOZED

    /** True for states that never ring again. */
    val isTerminal: Boolean get() = !isActive
}
