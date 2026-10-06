package org.carddemo.xferfee.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.carddemo.xferfee.contracts.FeePolicy;
import org.carddemo.xferfee.contracts.FeeResult;
import org.carddemo.xferfee.contracts.FeeRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CobolFeePolicyTest {

    private static final FeePolicy POLICY = new CobolFeePolicy();

    private static FeeRule rule(String pct, String cap) {
        return new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal(cap),
                LocalDate.parse("2020-01-01"), LocalDate.parse("9999-12-31"));
    }

    private static FeeResult apply(String amount, String pct, String cap) {
        return POLICY.apply(new BigDecimal(amount), rule(pct, cap));
    }

    @ParameterizedTest(name = "BR-07 {0} x {1} -> {2}")
    @CsvSource({
            "2.00,   0.012500, 0.03",   // 0.025   half-even would give 0.02
            "5.20,   0.012500, 0.07",   // 0.065   half-even would give 0.06
            "3.00,   0.015000, 0.05",   // 0.045   half-even would give 0.04
            "7.00,   0.015000, 0.11",   // 0.105   half-even would give 0.10
            "5.00,   0.005000, 0.03",   // 0.025
            "1.00,   0.012500, 0.01",   // 0.0125  below the half
            "100.00, 0.012500, 1.25",
            "-2.00,  0.012500, -0.03",  // ROUNDED is away from zero for negatives too
    })
    void br07RoundsHalfUpToCents(String amount, String pct, String fee) {
        FeeResult result = apply(amount, pct, "25.00");
        assertEquals(new BigDecimal(fee), result.feeAmt());
        assertEquals("N", result.capApplied());
    }

    @Test
    void br08CapIsComparedAfterRounding() {
        // 2000.30 x 0.0125 = 25.00375 -> rounds to 25.00, which is not > 25.00.
        FeeResult result = apply("2000.30", "0.012500", "25.00");
        assertEquals(new BigDecimal("25.00"), result.feeAmt());
        assertEquals("N", result.capApplied());
    }

    @Test
    void br08HalfCentAboveCapRoundsUpThenCaps() {
        // 2000.40 x 0.0125 = 25.005 -> rounds to 25.01 > 25.00.
        FeeResult result = apply("2000.40", "0.012500", "25.00");
        assertEquals(new BigDecimal("25.00"), result.feeAmt());
        assertEquals("Y", result.capApplied());
    }

    @Test
    void br09FeeEqualToCapIsNotCapped() {
        FeeResult result = apply("2000.00", "0.012500", "25.00");
        assertEquals(new BigDecimal("25.00"), result.feeAmt());
        assertEquals("N", result.capApplied());
    }

    @Test
    void br09FeeAboveCapIsCapped() {
        FeeResult retail = apply("5000.00", "0.012500", "25.00");
        assertEquals(new BigDecimal("25.00"), retail.feeAmt());
        assertEquals("Y", retail.capApplied());
        FeeResult instl = apply("200000.00", "0.005000", "500.00");
        assertEquals(new BigDecimal("500.00"), instl.feeAmt());
        assertEquals("Y", instl.capApplied());
    }

    @Test
    void br10ZeroAmountIsZeroFeeAndNotCapped() {
        FeeResult result = apply("0.00", "0.012500", "0.00");
        assertEquals(new BigDecimal("0.00"), result.feeAmt());
        assertEquals("N", result.capApplied());
    }

    @Test
    void br10ZeroAmountStillRequiresARule() {
        assertThrows(NullPointerException.class, () -> POLICY.apply(BigDecimal.ZERO, null));
    }

    @Test
    void feeIsAlwaysScaleTwo() {
        assertEquals(2, apply("100", "0.0125", "25").feeAmt().scale());
        assertEquals(2, apply("5000", "0.0125", "25").feeAmt().scale());
        assertEquals(2, apply("0", "0.0125", "25").feeAmt().scale());
    }

    @Test
    void wsFeeAmtKeepsOnlyNineIntegerDigits() {
        // 999,999,999.99 x 1.5 = 1,499,999,999.985 -> ROUNDED 1,499,999,999.99 -> S9(09)V99 keeps 499,999,999.99.
        FeeResult result = apply("999999999.99", "1.500000", "999999999.99");
        assertEquals(new BigDecimal("499999999.99"), result.feeAmt());
        assertEquals("N", result.capApplied());
    }
}
