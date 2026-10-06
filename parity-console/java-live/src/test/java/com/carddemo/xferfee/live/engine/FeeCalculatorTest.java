package com.carddemo.xferfee.live.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    private static final FeeRule RETAIL_OLD = rule("RETAIL", "0.012500", "25.00");
    private static final FeeRule RETAIL_NEW = rule("RETAIL", "0.015000", "25.00");
    private static final FeeRule INSTL = rule("INSTL", "0.005000", "500.00");

    @ParameterizedTest(name = "{0} x {1}: HALF_UP {2}, HALF_EVEN {3}")
    @CsvSource({
        // fixtures/xferfee/half_cent
        "2.00, 0.012500, 0.03, 0.02",
        "5.20, 0.012500, 0.07, 0.06",
        "3.00, 0.015000, 0.05, 0.04",
        "7.00, 0.015000, 0.11, 0.10",
        "11.00, 0.015000, 0.17, 0.16",
        "5.00, 0.005000, 0.03, 0.02",
    })
    void halfCentTiesRoundHalfUp(String amount, String pct, String halfUp, String halfEven) {
        FeeRule rule = rule("X", pct, "999.00");
        assertThat(FeeCalculator.compute(new BigDecimal(amount), rule, RoundingMode.HALF_UP).amount())
                .isEqualByComparingTo(halfUp);
        assertThat(FeeCalculator.compute(new BigDecimal(amount), rule, RoundingMode.HALF_EVEN).amount())
                .isEqualByComparingTo(halfEven);
    }

    @Test
    void feeEqualToCapIsNotFlagged() {
        Fee fee = FeeCalculator.compute(new BigDecimal("2000.00"), RETAIL_OLD, RoundingMode.HALF_UP);
        assertThat(fee.amount()).isEqualByComparingTo("25.00");
        assertThat(fee.capApplied()).isFalse();
    }

    @Test
    void feeAboveCapIsCappedAndFlagged() {
        Fee retail = FeeCalculator.compute(new BigDecimal("5000.00"), RETAIL_NEW, RoundingMode.HALF_UP);
        assertThat(retail.amount()).isEqualByComparingTo("25.00");
        assertThat(retail.capApplied()).isTrue();
        Fee instl = FeeCalculator.compute(new BigDecimal("200000.00"), INSTL, RoundingMode.HALF_UP);
        assertThat(instl.amount()).isEqualByComparingTo("500.00");
        assertThat(instl.capApplied()).isTrue();
    }

    @Test
    void capIsAppliedAfterRounding() {
        // 2000.40 x 1.25% = 25.005 -> rounds to 25.01 (> cap) -> capped and flagged.
        Fee fee = FeeCalculator.compute(new BigDecimal("2000.40"), RETAIL_OLD, RoundingMode.HALF_UP);
        assertThat(fee.amount()).isEqualByComparingTo("25.00");
        assertThat(fee.capApplied()).isTrue();
    }

    @Test
    void zeroAmountHasZeroFee() {
        Fee fee = FeeCalculator.compute(new BigDecimal("0.00"), RETAIL_OLD, RoundingMode.HALF_UP);
        assertThat(fee.amount()).isEqualByComparingTo("0.00");
        assertThat(fee.capApplied()).isFalse();
    }

    @Test
    void targetAccountComesFromDescriptionPosition14() {
        assertThat(JdbcTransferFeeEngine.targetAccountId("XFER TO ACCT 00000000002")).isEqualTo(2L);
        assertThat(JdbcTransferFeeEngine.targetAccountId("POS purchase")).isNull();
    }

    private static FeeRule rule(String book, String pct, String cap) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal(cap), LocalDate.of(2020, 1, 1),
                LocalDate.of(9999, 12, 31));
    }
}
