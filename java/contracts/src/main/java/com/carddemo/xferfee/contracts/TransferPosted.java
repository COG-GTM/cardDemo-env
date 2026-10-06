package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transfer whose fee was posted by STEP020 (XFERFEE); one record of
 * {@code XFER.FEES} (copybook CVXFR02Y). Text fields keep their legacy values.
 */
public record TransferPosted(
        LocalDate businessDate,
        String tranId,
        String tranDate,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        BigDecimal tranAmt,
        BigDecimal feePct,
        BigDecimal feeAmt,
        boolean capApplied,
        String ruleEffDate) {
}
