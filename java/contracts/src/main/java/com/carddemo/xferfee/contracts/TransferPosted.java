package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A posted transfer with its fee; the {@code XFER.FEES} record (CVXFR02Y). */
public record TransferPosted(
        String tranId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate tranDate,
        long sourceAccountId,
        long targetAccountId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmount,
        boolean capApplied,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate ruleEffectiveDate) {
}
