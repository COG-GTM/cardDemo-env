package com.carddemo.xfer.contracts;

/**
 * A type-08 transfer that could not be resolved. Counted in CBXFR01C's
 * {@code UNMATCHED CARDS} total and drives STEP010 to RC 4.
 *
 * @param tranId          TRAN-ID of the rejected transaction
 * @param cardNumber      TRAN-CARD-NUM of the rejected transaction
 * @param sourceAccountId cross-referenced account, or {@code null} when the card was not found
 * @param reason          why the transfer was rejected
 */
public record TransferRejected(
        String tranId,
        String cardNumber,
        String sourceAccountId,
        Reason reason) {

    public enum Reason {
        CARD_NOT_FOUND,
        ACCOUNT_NOT_FOUND
    }
}
