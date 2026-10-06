package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Replay-only stand-in for the CTL_XFER_PARM lookup (BR-06), used while no {@link FeeSchedule}
 * bean (COG-1236) is on the classpath: {@code EFF_DT <= business date < EXP_DT}. Like the
 * singleton {@code SELECT ... INTO}, more than one matching row is an error (SQLCODE -811).
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
        List<FeeRule> matches = rules.stream()
                .filter(rule -> rule.bookId().equals(bookId))
                .filter(rule -> !rule.effectiveDate().isAfter(businessDate) && rule.expiryDate().isAfter(businessDate))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(matches.size() + " CTL_XFER_PARM rows for " + bookId + " on " + businessDate);
        }
        return matches.stream().findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
