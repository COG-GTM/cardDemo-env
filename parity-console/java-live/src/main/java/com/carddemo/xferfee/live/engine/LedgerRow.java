package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/** XFER_FEE_LEDGER row plus the rate and rule date also written to XFER.FEES. */
public record LedgerRow(
        String tranId,
        LocalDate tranDt,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        BigDecimal tranAmt,
        BigDecimal feePct,
        BigDecimal feeAmt,
        String capApplied,
        LocalDate ruleEffDt) {
}
