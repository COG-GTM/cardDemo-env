package com.carddemo.parity.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of pushing one daily-transaction record through the xferfee rules.
 *
 * @param selected true when CBXFR01C would extract it (type 08, card and account resolved)
 */
public record TransferOutcome(
        String tranId,
        String typeCode,
        boolean selected,
        String skipReason,
        String book,
        BigDecimal amount,
        BigDecimal feePct,
        BigDecimal feeCap,
        String ruleEffDate,
        BigDecimal fee,
        String capApplied,
        Long srcAcctId,
        Long tgtAcctId,
        BigDecimal srcBalanceAfter,
        BigDecimal tgtBalanceAfter,
        LedgerRow ledger,
        byte[] extractRecord,
        byte[] feeRecord,
        List<String> extractMessages) {
}
