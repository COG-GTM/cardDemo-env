package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A selected type-08 transfer (one CVXFR01Y extract record written by CBXFR01C).
 *
 * @param tranId          XFR-TRAN-ID
 * @param tranDate        XFR-TRAN-DT (first 10 bytes of TRAN-ORIG-TS)
 * @param sourceAccountId XFR-SRC-ACCT-ID, resolved through CARDXREF
 * @param targetAccountId XFR-TGT-ACCT-ID, TRAN-DESC(14:11)
 * @param bookId          XFR-BOOK-ID, the source account's ACCT-GROUP-ID
 * @param amount          XFR-TRAN-AMT, scale 2
 * @param cardNumber      XFR-CARD-NUM
 */
public record TransferRequested(
        String tranId,
        LocalDate tranDate,
        long sourceAccountId,
        long targetAccountId,
        String bookId,
        BigDecimal amount,
        String cardNumber) {
}
