package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** Posting step finished; {@code rc} is the XFERFEE return code. */
public record BatchPosted(
        String runId,
        long posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal feeTotal,
        int rc) implements XferEvent {
}
