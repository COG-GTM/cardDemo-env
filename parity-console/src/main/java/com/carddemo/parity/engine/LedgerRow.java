package com.carddemo.parity.engine;

import java.math.BigDecimal;

/** One XFER_FEE_LEDGER row as inserted by XFERFEE. */
public record LedgerRow(
        String tranId,
        String tranDate,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        BigDecimal tranAmt,
        BigDecimal feeAmt,
        String capApplied) {

    public String toCsv() {
        return String.join(",", tranId, tranDate, Long.toString(srcAcctId), Long.toString(tgtAcctId),
                bookId, tranAmt.toPlainString(), feeAmt.toPlainString(), capApplied);
    }
}
