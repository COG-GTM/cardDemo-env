package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Replay-only stand-in until fee-schedule-service (COG-1236) registers a {@link FeeSchedule} bean:
 * the seeded CTL_XFER_PARM rows, matched on {@code EFF_DT <= date < EXP_DT} (BR-06); more than one
 * match is an error, as in the legacy lookup.
 */
class InterimFeeSchedule implements FeeSchedule {

    private volatile List<FeeRule> rules = List.of();

    @Override
    public void seed(List<FeeRule> seeded) {
        rules = seeded.stream()
                .sorted(Comparator.comparing(FeeRule::bookId).thenComparing(FeeRule::effectiveDate))
                .toList();
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        List<FeeRule> matches = rules.stream()
                .filter(rule -> rule.bookId().equals(bookId.strip()))
                .filter(rule -> !rule.effectiveDate().isAfter(businessDate))
                .filter(rule -> rule.expiryDate().isAfter(businessDate))
                .toList();
        if (matches.size() > 1) {
            // XFERFEE's single-row SELECT ... INTO fails (SQLCODE -811) instead of picking one.
            throw new IllegalStateException(matches.size() + " CTL_XFER_PARM rows match book "
                    + bookId.strip() + " on " + businessDate + " (SQLCODE -811)");
        }
        return matches.stream().findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
