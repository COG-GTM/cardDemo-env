package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One XFER_FEE_LEDGER row, keyed by {@code tranId} (BR-14). */
public record LedgerEntry(
        String tranId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate tranDate,
        long sourceAccountId,
        long targetAccountId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmount,
        boolean capApplied) {
}
