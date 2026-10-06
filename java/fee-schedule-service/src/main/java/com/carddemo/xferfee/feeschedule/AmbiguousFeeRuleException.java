package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.time.LocalDate;
import java.util.List;

public class AmbiguousFeeRuleException extends RuntimeException {

    private final List<FeeRule> matches;

    public AmbiguousFeeRuleException(String bookId, LocalDate date, List<FeeRule> matches) {
        super(matches.size() + " fee rules are effective for book " + bookId + " on " + date);
        this.matches = List.copyOf(matches);
    }

    public List<FeeRule> matches() {
        return matches;
    }
}
