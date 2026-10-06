package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Replay-only stand-in for the CTL_XFER_PARM lookup (BR-06), used while no {@link FeeSchedule}
 * bean (COG-1236) is on the classpath: {@code EFF_DT <= business date < EXP_DT}.
 */
final class FixtureFeeSchedule implements FeeSchedule {

    private static final Comparator<FeeRule> DUMP_ORDER =
            Comparator.comparing(FeeRule::bookId).thenComparing(FeeRule::effectiveDate);

    private List<FeeRule> rules = List.of();

    @Override
    public void seed(List<FeeRule> rules) {
        this.rules = rules.stream().sorted(DUMP_ORDER).toList();
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        return rules.stream()
                .filter(rule -> rule.bookId().equals(bookId))
                .filter(rule -> !rule.effectiveDate().isAfter(businessDate) && rule.expiryDate().isAfter(businessDate))
                .findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
