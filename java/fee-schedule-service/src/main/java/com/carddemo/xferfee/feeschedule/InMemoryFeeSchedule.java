package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@link FeeSchedule} for parity replay: same BR-06 semantics as the JDBC variant, no database. */
public class InMemoryFeeSchedule implements FeeSchedule {

    private volatile List<FeeRule> rules = List.of();

    /** Loads legacy rows exactly as given; overlaps are not rejected (the legacy table allows them). */
    @Override
    public void seed(List<FeeRule> rules) {
        this.rules = rules.stream().map(FeeRules::normalise).sorted(FeeRules.TABLE_ORDER).toList();
    }

    @Override
    public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
        String book = FeeRules.normaliseBook(bookId);
        List<FeeRule> matches = rules.stream()
                .filter(rule -> rule.bookId().equals(book))
                .filter(rule -> FeeRules.isEffectiveOn(rule, businessDate))
                .toList();
        return FeeRules.single(book, businessDate, matches);
    }

    @Override
    public List<FeeRule> rules() {
        return rules;
    }
}
