package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** BR-06: {@code EFF_DT <= business date < EXP_DT}, book id compared trimmed. */
public class InterimFeeSchedule implements FeeSchedule {

    private List<FeeRule> rules = List.of();

    @Override
    public void seed(List<FeeRule> rules) {
        List<FeeRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(FeeRule::bookId).thenComparing(FeeRule::effectiveDate));
        this.rules = List.copyOf(sorted);
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        String book = bookId == null ? "" : bookId.trim();
        return rules.stream()
                .filter(rule -> rule.bookId().trim().equals(book))
                .filter(rule -> !rule.effectiveDate().isAfter(businessDate))
                .filter(rule -> rule.expiryDate().isAfter(businessDate))
                .findFirst();
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
