package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.util.List;

public class OverlappingFeeRuleException extends RuntimeException {

    private final List<FeeRule> existing;

    public OverlappingFeeRuleException(FeeRule rule, List<FeeRule> existing) {
        super("fee rule for book " + rule.bookId() + " [" + rule.effectiveDate() + ", " + rule.expiryDate()
                + ") overlaps " + existing.size() + " existing rule(s)");
        this.existing = List.copyOf(existing);
    }

    public List<FeeRule> existing() {
        return existing;
    }
}
