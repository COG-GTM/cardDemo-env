package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One effective-dated row of CTL_XFER_PARM. */
public record FeeRule(
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeCap,
        LocalDate effDt,
        LocalDate expDt) {
}
