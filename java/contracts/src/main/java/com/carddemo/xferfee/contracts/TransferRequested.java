package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A selected type-08 transfer resolved to accounts and book (copybook CVXFR01Y). */
public record TransferRequested(
        String tranId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate tranDt,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tranAmt,
        String cardNum) {
}
