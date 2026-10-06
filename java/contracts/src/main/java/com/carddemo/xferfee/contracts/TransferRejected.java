package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A transfer that STEP020 (XFERFEE) did not post. */
public record TransferRejected(
        LocalDate businessDate,
        String tranId,
        String bookId,
        BigDecimal tranAmt,
        String reason) {
}
