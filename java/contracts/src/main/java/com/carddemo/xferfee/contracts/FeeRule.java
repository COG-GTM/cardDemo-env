package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One CTL_XFER_PARM row. A rule applies when {@code effectiveDate <= tranDate < expiryDate}.
 *
 * @param bookId        BOOK_ID, CHAR(10) with trailing blanks trimmed
 * @param feePct        FEE_PCT, DECIMAL(7,6)
 * @param feeCap        FEE_CAP, DECIMAL(11,2)
 * @param effectiveDate EFF_DT
 * @param expiryDate    EXP_DT (exclusive)
 */
public record FeeRule(
        String bookId,
        BigDecimal feePct,
        BigDecimal feeCap,
        LocalDate effectiveDate,
        LocalDate expiryDate) {

    public boolean appliesOn(LocalDate date) {
        return !effectiveDate.isAfter(date) && expiryDate.isAfter(date);
    }
}
