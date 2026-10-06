package com.carddemo.xferfee.reconciliation;

public enum DayStatus {
    /** Still accepting posted/rejected events. */
    OPEN,
    /** Closed with at least one fee; legacy RC 0. */
    CLOSED,
    /** Closed with no fee records (BR-19); legacy RC 4. */
    NO_FEES,
    /** Posting ended above RC 4, so no report is produced (COND=(4,LT,STEP020)). */
    SKIPPED
}
