package com.carddemo.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FeeRuleTest {

    private static FeeRule rule(String book, String eff, String exp) {
        return new FeeRule(book, new BigDecimal("0.015000"), new BigDecimal("25.00"),
                LocalDate.parse(eff), LocalDate.parse(exp));
    }

    @Test
    void windowIsHalfOpen() {
        FeeRule rule = rule("RETAIL", "2020-01-01", "2024-06-15");

        assertThat(rule.coversDate(LocalDate.parse("2019-12-31"))).isFalse();
        assertThat(rule.coversDate(LocalDate.parse("2020-01-01"))).isTrue();
        assertThat(rule.coversDate(LocalDate.parse("2024-06-14"))).isTrue();
        assertThat(rule.coversDate(LocalDate.parse("2024-06-15"))).isFalse();
    }

    @Test
    void adjacentWindowsDoNotOverlap() {
        FeeRule old = rule("RETAIL", "2020-01-01", "2024-06-15");

        assertThat(old.overlaps(rule("RETAIL", "2024-06-15", "9999-12-31"))).isFalse();
        assertThat(old.overlaps(rule("RETAIL", "2024-06-14", "9999-12-31"))).isTrue();
        assertThat(old.overlaps(rule("INSTL", "2020-01-01", "9999-12-31"))).isFalse();
    }

    @Test
    void bookIdLosesCharPadding() {
        assertThat(rule("RETAIL    ", "2020-01-01", "2024-06-15").bookId()).isEqualTo("RETAIL");
    }
}
