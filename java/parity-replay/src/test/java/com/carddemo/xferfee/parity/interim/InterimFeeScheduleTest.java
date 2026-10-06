package com.carddemo.xferfee.parity.interim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.posting.RuleLookupException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterimFeeScheduleTest {

    private static FeeRule rule(String pct, LocalDate from, LocalDate to) {
        return new FeeRule("RETAIL", new BigDecimal(pct), new BigDecimal("25.00"), from, to);
    }

    @Test
    void picksTheRuleWhoseWindowContainsTheDateWithExclusiveExpiry() {
        InterimFeeSchedule schedule = new InterimFeeSchedule();
        FeeRule old = rule("0.012500", LocalDate.of(2020, 1, 1), LocalDate.of(2024, 6, 15));
        FeeRule current = rule("0.015000", LocalDate.of(2024, 6, 15), LocalDate.of(9999, 12, 31));
        schedule.seed(List.of(current, old));

        assertThat(schedule.effectiveRule("RETAIL    ", LocalDate.of(2024, 6, 14))).contains(old);
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.of(2024, 6, 15))).contains(current);
        assertThat(schedule.effectiveRule("INSTL", LocalDate.of(2024, 6, 15))).isEmpty();
    }

    @Test
    void overlappingWindowsFailLikeTheSingleRowLegacyLookup() {
        InterimFeeSchedule schedule = new InterimFeeSchedule();
        schedule.seed(List.of(rule("0.010000", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)),
                rule("0.020000", LocalDate.of(2024, 6, 1), LocalDate.of(2025, 1, 1))));

        assertThatExceptionOfType(RuleLookupException.class)
                .isThrownBy(() -> schedule.effectiveRule("RETAIL", LocalDate.of(2024, 7, 1)))
                .withMessage("2 CTL_XFER_PARM rows match book RETAIL on 2024-07-01")
                .extracting(RuleLookupException::sqlCode).isEqualTo(-811);
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.of(2024, 3, 1))).isPresent();
    }
}
