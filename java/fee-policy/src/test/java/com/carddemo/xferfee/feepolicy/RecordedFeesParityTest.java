package com.carddemo.xferfee.feepolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.feepolicy.FeesFixtures.FeeRow;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every row of every COBOL-recorded {@code XFER.FEES} dataset, replayed through the library. */
class RecordedFeesParityTest {

    private final FeePolicy policy = new HalfUpFeePolicy();

    static Stream<FeeRow> recordedRows() {
        return FeesFixtures.allRows().stream();
    }

    @Test
    void everyFixtureContributesRows() {
        List<String> cases = FeesFixtures.cases();
        assertTrue(cases.containsAll(List.of("half_cent", "at_cap", "under_cap", "zero_amount", "rate_change")),
                "expected ticket cases in " + cases);
        for (String c : cases) {
            assertFalse(FeesFixtures.rows(c).isEmpty(), c + " has no XFER.FEES rows");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedRows")
    void feeAndCapMatchCobol(FeeRow row) {
        var rule = FeesFixtures.recordedRule(row);
        assertEquals(0, rule.feePct().compareTo(row.feePct()), "XFE-FEE-PCT vs CTL_XFER_PARM.FEE_PCT");

        FeeResult result = policy.apply(row.amount(), rule);

        assertEquals(row.feeAmount(), result.feeAmount(), "XFE-FEE-AMT");
        assertEquals(row.capApplied(), FeesFixtures.flag(result), "XFE-CAP-APPLIED");
    }

    /** Mirrors {@code tools/parity/naive_ref.py}: proves the fixture test catches HALF_EVEN. */
    @Test
    void halfEvenVariantFailsHalfCent() {
        FeePolicy halfEven = new HalfEvenFeePolicy();
        long mismatches = FeesFixtures.rows("half_cent").stream()
                .filter(row -> !matches(halfEven, row))
                .count();
        assertTrue(mismatches > 0, "HALF_EVEN must diverge from COBOL ROUNDED on half_cent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"under_cap", "at_cap", "zero_amount", "rate_change"})
    void halfEvenVariantOnlyDivergesOnTies(String caseName) {
        FeePolicy halfEven = new HalfEvenFeePolicy();
        assertTrue(FeesFixtures.rows(caseName).stream().allMatch(row -> matches(halfEven, row)),
                caseName + " has no half-cent ties, so HALF_EVEN should agree");
    }

    private static boolean matches(FeePolicy candidate, FeeRow row) {
        FeeResult result = candidate.apply(row.amount(), FeesFixtures.recordedRule(row));
        return result.feeAmount().equals(row.feeAmount()) && FeesFixtures.flag(result).equals(row.capApplied());
    }
}
