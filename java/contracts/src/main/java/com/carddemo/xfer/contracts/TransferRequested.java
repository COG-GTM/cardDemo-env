package com.carddemo.xfer.contracts;

import java.math.BigDecimal;

/**
 * A type-08 transfer resolved to its source account and book. Equivalent of one
 * {@code XFER-EXTRACT-RECORD} (copybook CVXFR01Y) written by CBXFR01C.
 *
 * @param tranId          XFR-TRAN-ID, from TRAN-ID
 * @param tranDate        XFR-TRAN-DT, business date = TRAN-ORIG-TS(1:10)
 * @param sourceAccountId XFR-SRC-ACCT-ID, from the card cross-reference
 * @param targetAccountId XFR-TGT-ACCT-ID, TRAN-DESC(14:11) as carried in the input
 * @param bookId          XFR-BOOK-ID, the source account's ACCT-GROUP-ID
 * @param amount          XFR-TRAN-AMT, scale 2
 * @param cardNumber      XFR-CARD-NUM, from TRAN-CARD-NUM
 */
public record TransferRequested(
        String tranId,
        String tranDate,
        String sourceAccountId,
        String targetAccountId,
        String bookId,
        BigDecimal amount,
        String cardNumber) {
}
