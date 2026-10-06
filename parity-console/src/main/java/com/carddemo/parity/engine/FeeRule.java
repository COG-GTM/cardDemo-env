package com.carddemo.parity.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One CTL_XFER_PARM row: fee rate and cap for a book within [effective, expiry). */
public record FeeRule(String bookId, BigDecimal pct, BigDecimal cap, LocalDate effective, LocalDate expiry) {

    public boolean appliesTo(String book, LocalDate date) {
        return bookId.strip().equals(book.strip()) && !effective.isAfter(date) && expiry.isAfter(date);
    }
}
