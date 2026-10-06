package com.carddemo.xferfee.observability;

import com.carddemo.xferfee.contracts.TransferRejected;
import java.time.Instant;
import java.util.List;

/** What an operator needs to replay or repair a run that abended (RC 8+). */
public record DeadLetterEntry(
        String chain,
        String runDate,
        ChainStep step,
        String program,
        int returnCode,
        String reason,
        List<TransferRejected> rejected,
        List<String> sysout,
        Instant enqueuedAt) {
}
