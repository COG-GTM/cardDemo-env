package com.carddemo.xferfee.observability;

/**
 * Maps a step return code the way operators read the job log today: RC 0 clean, RC 4 a warning
 * (BR-05 unmatched transfers, BR-19 empty report) that lets the chain continue, RC 8 or higher an
 * abend (BR-07, BR-12, BR-14) that needs someone to act.
 */
public final class ReturnCodePolicy {

    public static final int WARNING_RC = 4;
    public static final int ALERT_RC = 8;

    private ReturnCodePolicy() {
    }

    public static Severity classify(int returnCode) {
        if (returnCode >= ALERT_RC) {
            return Severity.ALERT;
        }
        if (returnCode > 0) {
            return Severity.WARNING;
        }
        return Severity.OK;
    }
}
