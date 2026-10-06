package com.carddemo.posting;

/** A transfer that cannot be posted; {@link #legacyMessage()} is the XFERFEE SYSOUT line. */
public class PostingException extends RuntimeException {

    public enum Reason { NO_FEE_RULE, RULE_LOOKUP_FAILED, ACCOUNT_NOT_FOUND, LEDGER_INSERT_FAILED }

    private final Reason reason;
    private final String tranId;

    public PostingException(Reason reason, String tranId, String legacyMessage, Throwable cause) {
        super(legacyMessage, cause);
        this.reason = reason;
        this.tranId = tranId;
    }

    public Reason reason() {
        return reason;
    }

    public String tranId() {
        return tranId;
    }

    public String legacyMessage() {
        return getMessage();
    }
}
