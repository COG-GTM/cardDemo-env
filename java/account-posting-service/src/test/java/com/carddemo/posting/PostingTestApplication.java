package com.carddemo.posting;

import com.carddemo.contracts.FeePolicy;
import com.carddemo.contracts.FeeResult;
import com.carddemo.contracts.FeeRule;
import com.carddemo.contracts.FeeSchedule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(PostingConfiguration.class)
class PostingTestApplication {

    static final List<FeeRule> RULES = List.of(
            new FeeRule("RETAIL", new BigDecimal("0.012500"), new BigDecimal("25.00"),
                    LocalDate.parse("2020-01-01"), LocalDate.parse("2024-06-15")),
            new FeeRule("RETAIL", new BigDecimal("0.015000"), new BigDecimal("25.00"),
                    LocalDate.parse("2024-06-15"), LocalDate.parse("9999-12-31")),
            new FeeRule("INSTL", new BigDecimal("0.005000"), new BigDecimal("500.00"),
                    LocalDate.parse("2020-01-01"), LocalDate.parse("9999-12-31")));

    @Bean
    FeeSchedule feeSchedule() {
        return (bookId, date) -> {
            List<FeeRule> matches = RULES.stream()
                    .filter(r -> r.bookId().equals(bookId.strip()))
                    .filter(r -> !r.effectiveDate().isAfter(date) && r.expiryDate().isAfter(date))
                    .toList();
            return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
        };
    }

    @Bean
    FeePolicy feePolicy() {
        return (amount, rule) -> {
            if (amount.signum() == 0) {
                return new FeeResult(new BigDecimal("0.00"), false);
            }
            BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
            return fee.compareTo(rule.feeCap()) > 0 ? new FeeResult(rule.feeCap(), true) : new FeeResult(fee, false);
        };
    }
}
