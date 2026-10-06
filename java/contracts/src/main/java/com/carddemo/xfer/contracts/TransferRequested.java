package com.carddemo.xfer.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** One XFER-EXTRACT-RECORD (CVXFR01Y). */
public record TransferRequested(
        String runId,
        long seq,
        String tranId,
        String tranDt,
        long srcAcctId,
        long tgtAcctId,
        String bookId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tranAmt,
        String cardNum) implements XferEvent {
}
