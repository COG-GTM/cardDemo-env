package com.carddemo.xferfee.recon;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** True total for one book (or all books), independent of input order. */
public record BookTotal(
        String bookId,
        long count,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee) {
}
