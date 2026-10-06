package com.carddemo.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A posted transfer with its fee (copybook CVXFR02Y / XFER_FEE_LEDGER row). */
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

    public String capAppliedFlag() {
        return capApplied ? "Y" : "N";
    }
}
