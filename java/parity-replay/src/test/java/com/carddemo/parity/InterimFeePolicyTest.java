package com.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.contracts.FeeResult;
import com.carddemo.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InterimFeePolicyTest {

    private final InterimFeePolicy policy = new InterimFeePolicy();

    @ParameterizedTest
    @CsvSource({
            "2.00, 0.012500, 25.00, 0.03, false",
            "5.20, 0.012500, 25.00, 0.07, false",
            "5.00, 0.005000, 500.00, 0.03, false",
            "2000.00, 0.012500, 25.00, 25.00, false",
            "2000.80, 0.012500, 25.00, 25.00, true",
            "0.00, 0.015000, 25.00, 0.00, false",
            "1999.99, 0.012500, 25.00, 25.00, false"})
    void halfUpThenStrictCap(String amount, String pct, String cap, String fee, boolean capped) {
        FeeRule rule = new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal(cap),
                LocalDate.parse("2020-01-01"), LocalDate.parse("9999-12-31"));

        FeeResult result = policy.apply(new BigDecimal(amount), rule);

        assertThat(result.feeAmount()).isEqualByComparingTo(fee);
        assertThat(result.feeAmount().scale()).isEqualTo(2);
        assertThat(result.capApplied()).isEqualTo(capped);
    }
}
