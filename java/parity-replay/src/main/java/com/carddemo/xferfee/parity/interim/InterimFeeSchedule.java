package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** In-memory CTL_XFER_PARM (BR-06). Replaced by fee-schedule-service (COG-1236). */
class InterimFeeSchedule implements FeeSchedule {

    private volatile List<FeeRule> rules = List.of();

    @Override
    public void seed(List<FeeRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparing(FeeRule::bookId).thenComparing(FeeRule::effectiveDate))
                .toList();
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        return rules.stream()
                .filter(r -> r.bookId().equals(bookId))
                .filter(r -> !r.effectiveDate().isAfter(businessDate) && r.expiryDate().isAfter(businessDate))
                .findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
