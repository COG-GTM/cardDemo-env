package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;

public record TransferResult(
        String tranId,
        TransferOutcome outcome,
        String rule,
        String reason,
        String roundingMode,
        String book,
        String businessDate,
        Long sourceAcctId,
        Long targetAcctId,
        BigDecimal amount,
        FeeRule feeRule,
        BigDecimal fee,
        String capApplied,
        Account sourceAfter,
        Account targetAfter,
        LedgerRow ledger) {

    static TransferResult notPosted(DailyTransaction tx, TransferOutcome outcome, String rule, String reason,
            String roundingMode) {
        return new TransferResult(tx.tranId(), outcome, rule, reason, roundingMode, null, null, null, null,
                tx.amount(), null, null, null, null, null, null);
    }
}
