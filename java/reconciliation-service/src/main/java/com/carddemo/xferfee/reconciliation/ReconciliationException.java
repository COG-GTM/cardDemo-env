package com.carddemo.xferfee.reconciliation;

public class ReconciliationException extends RuntimeException {

    public enum Kind { UNKNOWN_DAY, DAY_CLOSED, DAY_OPEN, NO_REPORT, CONFLICTING_DUPLICATE }

    private final Kind kind;

    public ReconciliationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
