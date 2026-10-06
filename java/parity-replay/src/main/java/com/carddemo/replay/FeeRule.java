package com.carddemo.replay;

import java.math.BigDecimal;

/** One CTL_XFER_PARM row. */
record FeeRule(String bookId, BigDecimal feePct, BigDecimal feeCap, String effDt, String expDt) {

    boolean matches(String book, String tranDt) {
        return bookId.strip().equals(book.strip())
                && effDt.compareTo(tranDt) <= 0
                && expDt.compareTo(tranDt) > 0;
    }
}
