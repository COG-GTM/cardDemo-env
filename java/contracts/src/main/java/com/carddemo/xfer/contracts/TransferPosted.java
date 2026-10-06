package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** One XFER-FEE-RECORD (CVXFR02Y) plus its ledger row. */
public record TransferPosted(
        String runId,
        long seq,
        String tranId,
        String tranDt,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tranAmt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmt,
        boolean capApplied,
        String ruleEffDt) implements XferEvent {
}
