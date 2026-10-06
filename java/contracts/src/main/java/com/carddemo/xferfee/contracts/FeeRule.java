package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One effective-dated row of CTL_XFER_PARM. {@code expDt} is exclusive. */
public record FeeRule(
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feePct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeCap,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate effDt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate expDt) {
}
