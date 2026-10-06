package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A transfer posted with its fee (copybook CVXFR02Y / XFER_FEE_LEDGER). */
public record TransferPosted(
        String tranId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate tranDt,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tranAmt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmt,
        boolean capApplied,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate ruleEffDt) {
}
