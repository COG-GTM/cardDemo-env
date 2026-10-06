package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** In-memory CTL_XFER_PARM seeded from the day's rule snapshot (BR-06 half-open window). */
public final class SnapshotFeeSchedule implements FeeSchedule {

    private List<FeeRule> rules = List.of();

    @Override
    public void seed(List<FeeRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        List<FeeRule> matches = matching(bookId, businessDate);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Every row whose {@code [EFF_DT, EXP_DT)} covers the date; in snapshot order. */
    public List<FeeRule> matching(String bookId, LocalDate businessDate) {
        return rules.stream()
                .filter(rule -> rule.bookId().strip().equals(bookId.strip()))
                .filter(rule -> !rule.effectiveDate().isAfter(businessDate) && rule.expiryDate().isAfter(businessDate))
                .toList();
    }

    @Override
    public List<FeeRule> rules() {
        return rules.stream()
                .sorted(Comparator.comparing((FeeRule rule) -> rule.bookId().strip())
                        .thenComparing(FeeRule::effectiveDate))
                .toList();
    }
}
