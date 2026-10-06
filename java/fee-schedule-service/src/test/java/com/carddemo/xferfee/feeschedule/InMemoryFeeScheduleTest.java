package com.carddemo.xferfee.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryFeeScheduleTest {

    private final InMemoryFeeSchedule schedule = new InMemoryFeeSchedule();

    private static FeeRule rule(String book, String pct, String eff, String exp) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal("25.00"), LocalDate.parse(eff),
                LocalDate.parse(exp));
    }

    @Test
    void windowIsHalfOpenEffectiveInclusiveExpiryExclusive() {
        schedule.seed(List.of(
                rule("RETAIL    ", "0.015000", "2024-06-15", "9999-12-31"),
                rule("RETAIL", "0.012500", "2020-01-01", "2024-06-15")));
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2024-06-14")))
                .map(FeeRule::feePct).contains(new BigDecimal("0.012500"));
        assertThat(schedule.effectiveRule("RETAIL    ", LocalDate.parse("2024-06-15")))
                .map(FeeRule::feePct).contains(new BigDecimal("0.015000"));
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2019-12-31"))).isEmpty();
        assertThat(schedule.effectiveRule("INSTL", LocalDate.parse("2024-06-20"))).isEmpty();
    }

    @Test
    void rulesAreDumpedByBookThenEffectiveDateWithTrimmedBooks() {
        schedule.seed(List.of(
                rule("RETAIL", "0.015000", "2024-06-15", "9999-12-31"),
                rule("INSTL     ", "0.005000", "2020-01-01", "9999-12-31"),
                rule("RETAIL", "0.012500", "2020-01-01", "2024-06-15")));
        assertThat(schedule.rules()).extracting(FeeRule::bookId, FeeRule::effectiveDate).containsExactly(
                org.assertj.core.groups.Tuple.tuple("INSTL", LocalDate.parse("2020-01-01")),
                org.assertj.core.groups.Tuple.tuple("RETAIL", LocalDate.parse("2020-01-01")),
                org.assertj.core.groups.Tuple.tuple("RETAIL", LocalDate.parse("2024-06-15")));
    }
}
