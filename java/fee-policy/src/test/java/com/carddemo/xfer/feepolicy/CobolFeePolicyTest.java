package com.carddemo.xfer.feepolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.carddemo.xfer.contracts.FeeResult;
import com.carddemo.xfer.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CobolFeePolicyTest {

    private final CobolFeePolicy policy = new CobolFeePolicy();

    @ParameterizedTest
    @CsvSource({
        "2.00, 0.012500, 25.00, 0.03, false",
        "5.20, 0.012500, 25.00, 0.07, false",
        "3.00, 0.015000, 25.00, 0.05, false",
        "5.00, 0.005000, 500.00, 0.03, false",
        "100.00, 0.015000, 25.00, 1.50, false",
        "2000.00, 0.015000, 25.00, 25.00, true",
        "1666.66, 0.015000, 25.00, 25.00, false",
        "0.00, 0.015000, 25.00, 0.00, false",
        "-2.00, 0.012500, 25.00, -0.03, false",
    })
    void matchesCobolRoundThenCap(String amount, String pct, String cap, String fee, boolean capped) {
        FeeRule rule = new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal(cap),
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31));
        FeeResult result = policy.apply(new BigDecimal(amount), rule);
        assertEquals(new BigDecimal(fee), result.feeAmt());
        assertEquals(capped, result.capApplied());
    }
}
