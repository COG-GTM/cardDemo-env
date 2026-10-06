package com.carddemo.xferfee.feepolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HalfUpFeePolicyTest {

    private static final FeeRule RETAIL_OLD = rule("0.012500", "25.00");
    private static final FeeRule RETAIL_NEW = rule("0.015000", "25.00");
    private static final FeeRule INSTL = rule("0.005000", "500.00");

    private final HalfUpFeePolicy policy = new HalfUpFeePolicy();

    @ParameterizedTest(name = "{0} x {1} -> {2}")
    @CsvSource({
        // BR-08: exact half cents round away from zero (HALF_EVEN would give the even cent)
        "2.00,   0.012500, 0.03",
        "5.20,   0.012500, 0.07",
        "7.00,   0.015000, 0.11",
        "11.00,  0.015000, 0.17",
        "5.00,   0.005000, 0.03",
        "3.00,   0.015000, 0.05",
        "-2.00,  0.012500, -0.03",
        // below half rounds down, above half rounds up
        "1.11,   0.015000, 0.02",
        "1.03,   0.015000, 0.02",
        "1.00,   0.012500, 0.01",
    })
    void roundsHalfUpToTheCent(String amount, String pct, String expected) {
        FeeResult result = policy.apply(new BigDecimal(amount), rule(pct, "999999.99"));
        assertEquals(new BigDecimal(expected), result.feeAmount());
        assertEquals("N", FeesFixtures.flag(result));
    }

    @Test
    void feeEqualToCapIsNotFlagged() {
        FeeResult result = policy.apply(new BigDecimal("2000.00"), RETAIL_OLD);
        assertEquals(new BigDecimal("25.00"), result.feeAmount());
        assertEquals("N", FeesFixtures.flag(result));
    }

    @Test
    void feeAboveCapIsCappedAndFlagged() {
        FeeResult result = policy.apply(new BigDecimal("200000.00"), INSTL);
        assertEquals(new BigDecimal("500.00"), result.feeAmount());
        assertEquals("Y", FeesFixtures.flag(result));
    }

    @Test
    void capIsComparedAfterRounding() {
        // 1666.70 x 0.015 = 25.0005 (> cap before rounding) rounds to 25.00 (= cap) -> not capped
        FeeResult result = policy.apply(new BigDecimal("1666.70"), RETAIL_NEW);
        assertEquals(new BigDecimal("25.00"), result.feeAmount());
        assertEquals("N", FeesFixtures.flag(result));

        // 1666.97 x 0.015 = 25.00455 rounds to 25.00 -> still equal, not flagged
        assertEquals("N", FeesFixtures.flag(policy.apply(new BigDecimal("1666.97"), RETAIL_NEW)));

        // 1667.00 x 0.015 = 25.005 rounds half-up to 25.01 (> cap) -> capped
        FeeResult over = policy.apply(new BigDecimal("1667.00"), RETAIL_NEW);
        assertEquals(new BigDecimal("25.00"), over.feeAmount());
        assertEquals("Y", FeesFixtures.flag(over));
    }

    @Test
    void zeroAmountHasZeroFeeAndNoCap() {
        FeeResult result = policy.apply(new BigDecimal("0.00"), RETAIL_NEW);
        assertEquals(new BigDecimal("0.00"), result.feeAmount());
        assertEquals("N", FeesFixtures.flag(result));
    }

    @Test
    void zeroAmountStillNeedsARule() {
        assertThrows(NullPointerException.class, () -> policy.apply(BigDecimal.ZERO, null));
    }

    @Test
    void feeAlwaysHasCentScale() {
        assertEquals(2, policy.apply(new BigDecimal("100"), RETAIL_OLD).feeAmount().scale());
        assertEquals(2, policy.apply(BigDecimal.ZERO, RETAIL_OLD).feeAmount().scale());
        assertEquals(2, policy.apply(new BigDecimal("5000.00"), rule("0.015000", "25")).feeAmount().scale());
    }

    private static FeeRule rule(String pct, String cap) {
        return new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal(cap),
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31));
    }
}
