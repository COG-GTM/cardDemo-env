package com.carddemo.posting;

import com.carddemo.contracts.TransferPosted;
import com.carddemo.contracts.TransferRejected;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of one posting run. {@code posted} holds every transfer processed before a batch-atomic
 * abend too (they were rolled back, but XFERFEE had already written their fee records).
 */
public record RunResult(int returnCode, boolean committed, List<TransferPosted> posted,
        List<TransferRejected> rejected, List<String> diagnostics) {

    public static final int RC_OK = 0;
    public static final int RC_REJECTS = 4;
    public static final int RC_ABEND = 8;

    public BigDecimal feeTotal() {
        return posted.stream().map(TransferPosted::feeAmount).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}
