package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/** CTL_XFER_PARM row; effective on [effDt, expDt). */
public record FeeRule(String bookId, BigDecimal feePct, BigDecimal feeCap, LocalDate effDt, LocalDate expDt) {
}
