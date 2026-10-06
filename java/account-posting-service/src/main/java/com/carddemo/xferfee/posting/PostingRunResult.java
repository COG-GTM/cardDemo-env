package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a run. {@code returnCode} follows the legacy step: 0 all posted, 8 batch abend (nothing
 * committed). Per-transfer mode returns 4 when any transfer was parked.
 */
public record PostingRunResult(
        int returnCode,
        List<TransferPosted> posted,
        List<TransferRejected> rejected,
        String abendMessage) {

    public boolean abended() {
        return abendMessage != null;
    }

    public BigDecimal totalFees() {
        return posted.stream().map(TransferPosted::feeAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
