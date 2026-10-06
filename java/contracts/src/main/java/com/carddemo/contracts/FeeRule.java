package com.carddemo.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One CTL_XFER_PARM row: applies when effectiveDate &lt;= tranDate &lt; expiryDate. */
public record FeeRule(
        String bookId,
        BigDecimal feePct,
        BigDecimal feeCap,
        LocalDate effectiveDate,
        LocalDate expiryDate) {
}
