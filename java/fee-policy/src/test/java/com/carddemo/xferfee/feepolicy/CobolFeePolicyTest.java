package com.carddemo.xferfee.feepolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CobolFeePolicyTest {

    private final CobolFeePolicy policy = new CobolFeePolicy();

    private static FeeRule rule(String pct, String cap) {
        return new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal(cap),
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31));
    }

    @ParameterizedTest
    @CsvSource({
        "100.00, 0.015000, 25.00, 1.50, false",
        "1.00, 0.005000, 500.00, 0.01, false",
        "3.00, 0.005000, 500.00, 0.02, false",
        "5.00, 0.005000, 500.00, 0.03, false",
        "1666.67, 0.015000, 25.00, 25.00, false",
        "5000.00, 0.015000, 25.00, 25.00, true",
        "-100.00, 0.015000, 25.00, -1.50, false",
        "0.00, 0.015000, 25.00, 0.00, false",
    })
    void roundsHalfUpThenCapsStrictly(String amount, String pct, String cap, String fee, boolean capped) {
        FeeResult result = policy.apply(new BigDecimal(amount), rule(pct, cap));
        assertThat(result.feeAmount()).isEqualByComparingTo(fee);
        assertThat(result.feeAmount().scale()).isEqualTo(2);
        assertThat(result.capApplied()).isEqualTo(capped);
    }

    @Test
    void halfEvenWouldDisagreeOnHalfCents() {
        BigDecimal raw = new BigDecimal("1.00").multiply(new BigDecimal("0.005000"));
        assertThat(raw.setScale(2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.00");
        assertThat(policy.apply(new BigDecimal("1.00"), rule("0.005000", "500.00")).feeAmount())
                .isEqualByComparingTo("0.01");
    }
}
