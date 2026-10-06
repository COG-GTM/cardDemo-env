package org.carddemo.xferfee.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One CTL_XFER_PARM row: {@code feePct} is FEE_PCT (S9(1)V9(6)), {@code feeCap} is FEE_CAP (S9(09)V99), valid for
 * transfer dates in {@code [effDt, expDt)}.
 */
public record FeeRule(String bookId, BigDecimal feePct, BigDecimal feeCap, LocalDate effDt, LocalDate expDt) {

    public FeeRule {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(feePct, "feePct");
        Objects.requireNonNull(feeCap, "feeCap");
        Objects.requireNonNull(effDt, "effDt");
        Objects.requireNonNull(expDt, "expDt");
    }

    public boolean covers(LocalDate tranDt) {
        return !effDt.isAfter(tranDt) && expDt.isAfter(tranDt);
    }
}
