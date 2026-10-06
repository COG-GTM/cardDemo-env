package com.carddemo.feeschedule;

import java.util.List;

public class OverlappingFeeRuleException extends RuntimeException {

    public OverlappingFeeRuleException(FeeRule rule, List<FeeRule> overlapping) {
        super("Fee rule " + rule.bookId() + " [" + rule.effDt() + ", " + rule.expDt()
                + ") overlaps " + overlapping.stream()
                        .map(r -> "[" + r.effDt() + ", " + r.expDt() + ")")
                        .toList());
    }
}
