package com.carddemo.contracts;

/** Legacy step return-code classes emitted by the XFERFEEP chain. */
public enum StepReturnCode {
    OK("ok", null),
    WARNING("warning", "warning"),
    ABEND("abend", "critical");

    private final String outcome;
    private final String alertSeverity;

    StepReturnCode(String outcome, String alertSeverity) {
        this.outcome = outcome;
        this.alertSeverity = alertSeverity;
    }

    public static StepReturnCode of(int returnCode) {
        if (returnCode == 0) {
            return OK;
        }
        return returnCode < 8 ? WARNING : ABEND;
    }

    public String outcome() {
        return outcome;
    }

    public String alertSeverity() {
        return alertSeverity;
    }
}
