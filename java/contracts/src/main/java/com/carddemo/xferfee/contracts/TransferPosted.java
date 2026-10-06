package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transfer posted by XFERFEE: one CVXFR02Y fee record and one XFER_FEE_LEDGER row.
 *
 * @param tranId            XFE-TRAN-ID / TRAN_ID
 * @param tranDate          XFE-TRAN-DT / TRAN_DT
 * @param sourceAccountId   XFE-SRC-ACCT-ID / SRC_ACCT_ID
 * @param targetAccountId   XFE-TGT-ACCT-ID / TGT_ACCT_ID
 * @param bookId            XFE-BOOK-ID / BOOK_ID
 * @param amount            XFE-TRAN-AMT / TRAN_AMT, scale 2
 * @param feePct            XFE-FEE-PCT, scale 6
 * @param feeAmount         XFE-FEE-AMT / FEE_AMT, scale 2
 * @param capApplied        XFE-CAP-APPLIED / CAP_APPLIED ("Y" when true)
 * @param ruleEffectiveDate XFE-RULE-EFF-DT, EFF_DT of the CTL_XFER_PARM row used
 */
public record TransferPosted(
        String tranId,
        LocalDate tranDate,
        long sourceAccountId,
        long targetAccountId,
        String bookId,
        BigDecimal amount,
        BigDecimal feePct,
        BigDecimal feeAmount,
        boolean capApplied,
        LocalDate ruleEffectiveDate) {
}
