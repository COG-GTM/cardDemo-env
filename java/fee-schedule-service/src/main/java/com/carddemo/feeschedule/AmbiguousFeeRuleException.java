package com.carddemo.feeschedule;

import java.time.LocalDate;
import java.util.List;

public class AmbiguousFeeRuleException extends RuntimeException {

    public AmbiguousFeeRuleException(String bookId, LocalDate date, List<FeeRule> matches) {
        super(matches.size() + " fee rules for book " + bookId + " are effective on " + date);
    }
}
