package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** One DALYTRAN record (CVTRA05Y) as published by the ingress. */
public record DailyTranReceived(
        String runId,
        long seq,
        String tranId,
        String tranTypeCd,
        String tranDesc,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tranAmt,
        String tranCardNum,
        String tranOrigTs) implements XferEvent {
}
