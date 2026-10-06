package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** Outcome of {@link FeePolicy#apply}: the fee charged and whether the cap replaced it. */
public record FeeResult(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeAmount,
        boolean capApplied) {
}
