package org.carddemo.xferfee.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.carddemo.xferfee.contracts.FeePolicy;
import org.carddemo.xferfee.contracts.FeeResult;
import org.carddemo.xferfee.contracts.FeeRule;
import org.carddemo.xferfee.fee.XferFeesFixtures.FeeRow;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code tools/parity/naive_ref.py}: a {@code HALF_EVEN} policy must be caught by the {@code half_cent}
 * fixture, proving the parity test can tell the two rounding modes apart.
 */
class HalfEvenVariantMustFailTest {

    private static final FeePolicy HALF_EVEN = (BigDecimal amount, FeeRule rule) -> {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_EVEN);
        return fee.compareTo(rule.feeCap()) > 0
                ? new FeeResult(rule.feeCap().setScale(2), FeeResult.CAP_APPLIED)
                : new FeeResult(fee, FeeResult.CAP_NOT_APPLIED);
    };

    private static List<String> mismatches(FeePolicy policy, List<FeeRow> rows) {
        return rows.stream()
                .filter(row -> !policy.apply(row.tranAmt(), row.rule()).feeAmt().equals(row.feeAmt()))
                .map(FeeRow::tranId)
                .toList();
    }

    @Test
    void halfEvenFailsHalfCent() {
        List<String> diffs = mismatches(HALF_EVEN, XferFeesFixtures.rows("half_cent"));
        assertFalse(diffs.isEmpty(), "HALF_EVEN must not reproduce half_cent");
        // Every half_cent row lands exactly on a half cent: 0.025, 0.065, 0.045, 0.105, 0.165, 0.025.
        assertEquals(List.of("TRN0000000000001", "TRN0000000000002", "TRN0000000000003",
                "TRN0000000000004", "TRN0000000000005", "TRN0000000000006"), diffs);
    }

    @Test
    void cobolPolicyPassesHalfCent() {
        assertEquals(List.of(), mismatches(new CobolFeePolicy(), XferFeesFixtures.rows("half_cent")));
    }

    @Test
    void halfEvenStillPassesTheNonTieCases() {
        for (String c : List.of("under_cap", "at_cap", "zero_amount", "rate_change")) {
            assertEquals(List.of(), mismatches(HALF_EVEN, XferFeesFixtures.rows(c)), c);
        }
    }
}
