package org.carddemo.xferfee.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.carddemo.xferfee.contracts.FeeResult;
import org.carddemo.xferfee.fee.XferFeesFixtures.FeeRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every row of every COBOL-recorded {@code XFER.FEES} dataset must be reproduced by {@link CobolFeePolicy}. */
class FixtureFeesParityTest {

    static Stream<FeeRow> recordedFeeRows() {
        return XferFeesFixtures.allRows().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedFeeRows")
    void matchesCobolRecordedFee(FeeRow row) {
        assertEquals(0, row.rule().feePct().compareTo(row.feePct()), "XFE-FEE-PCT vs resolved rule");
        assertEquals(row.rule().effDt(), row.ruleEffDt(), "XFE-RULE-EFF-DT vs resolved rule");

        FeeResult result = new CobolFeePolicy().apply(row.tranAmt(), row.rule());

        assertEquals(row.feeAmt(), result.feeAmt(), "XFE-FEE-AMT");
        assertEquals(row.capApplied(), result.capApplied(), "XFE-CAP-APPLIED");
    }

    @Test
    void ticketParityCasesAreCovered() {
        List<String> cases = XferFeesFixtures.cases();
        assertTrue(cases.containsAll(Set.of("half_cent", "at_cap", "under_cap", "zero_amount", "rate_change")),
                "fixtures present: " + cases);
        assertTrue(XferFeesFixtures.allRows().size() >= 17, "expected every recorded XFER.FEES row");
    }
}
