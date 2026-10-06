package com.carddemo.xferfee.contracts;

/**
 * A transfer that could not be extracted or posted.
 *
 * @param tranId TRAN-ID of the rejected transaction
 * @param stage  legacy step that rejected it (STEP010, STEP020, ...)
 * @param reason machine-readable reason
 * @param detail the legacy DISPLAY text, kept verbatim for SYSOUT parity
 */
public record TransferRejected(String tranId, String stage, Reason reason, String detail) {

    public enum Reason {
        CARD_NOT_FOUND,
        ACCOUNT_NOT_FOUND,
        NO_FEE_RULE
    }
}
