package com.carddemo.replay;

import com.carddemo.observability.ChainStep;
import com.carddemo.observability.DeadLetterEntry;
import com.carddemo.observability.LegacyCounter;
import com.carddemo.observability.StepCounters;
import com.carddemo.observability.StepOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** XFERFEE: price each transfer from CTL_XFER_PARM and total the fees. */
final class FeePostingStep {

    private static final int TABLE_LIMIT = 500;
    private static final ChainStep STEP = ChainStep.STEP020;
    private static final BigDecimal NINE_DIGIT_MODULUS = BigDecimal.TEN.pow(9);

    record Result(StepOutcome outcome, List<FeeRecord> fees) {
    }

    Result run(FixtureCase fixture, List<Transfer> transfers) {
        Set<Long> accounts = new HashSet<>();
        for (byte[] r : fixture.accounts().subList(0, Math.min(TABLE_LIMIT, fixture.accounts().size()))) {
            accounts.add(FixedRecords.unsigned(r, 0, 11));
        }
        StepCounters counters = new StepCounters(STEP);
        List<FeeRecord> fees = new ArrayList<>();
        for (Transfer transfer : transfers) {
            Optional<FeeRule> rule = fixture.feeRules().stream()
                    .filter(r -> r.matches(transfer.bookId(), transfer.tranDt()))
                    .findFirst();
            if (rule.isEmpty()) {
                return abend(transfer, "NO_FEE_RULE",
                        STEP.program() + ": NO FEE RULE FOR BOOK " + transfer.bookId());
            }
            BigDecimal fee = BigDecimal.ZERO.setScale(2);
            if (transfer.amount().signum() != 0) {
                fee = transfer.amount().multiply(rule.get().feePct())
                        .setScale(2, RoundingMode.HALF_UP)
                        .remainder(NINE_DIGIT_MODULUS);
                if (fee.compareTo(rule.get().feeCap()) > 0) {
                    fee = rule.get().feeCap();
                }
            }
            if (!accounts.contains(transfer.srcAcctId()) || !accounts.contains(transfer.tgtAcctId())) {
                return abend(transfer, "POSTING_ACCOUNT_NOT_FOUND",
                        String.format("%s: ACCOUNT NOT FOUND %011d / %011d",
                                STEP.program(), transfer.srcAcctId(), transfer.tgtAcctId()));
            }
            fees.add(new FeeRecord(transfer.tranId(), transfer.bookId(), transfer.amount(), fee));
            counters.increment(LegacyCounter.TRANSFERS_POSTED);
            counters.add(LegacyCounter.TOTAL_FEES, fee);
        }
        return new Result(new StepOutcome(STEP, 0, counters, true, List.of(), List.of(), List.of()), fees);
    }

    /** 9999-ABEND-PROGRAM: RC 8, no COMMIT and no totals DISPLAYed. */
    private Result abend(Transfer transfer, String reason, String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("tranDt", transfer.tranDt());
        payload.put("srcAcctId", String.format("%011d", transfer.srcAcctId()));
        payload.put("tgtAcctId", String.format("%011d", transfer.tgtAcctId()));
        payload.put("bookId", transfer.bookId().strip());
        payload.put("amount", transfer.amount().toPlainString());
        DeadLetterEntry entry = new DeadLetterEntry(STEP.name(), STEP.program(), 8, reason,
                transfer.tranId().strip(), payload);
        StepOutcome outcome = new StepOutcome(STEP, 8, new StepCounters(STEP), false,
                List.of(message, STEP.program() + ": 9999-ABEND-PROGRAM"), List.of(), List.of(entry));
        return new Result(outcome, List.of());
    }
}
