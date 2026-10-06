package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** BR-06 rule selection shared by every {@code FeeSchedule} implementation. */
public final class FeeRules {

    public static final Comparator<FeeRule> DUMP_ORDER =
            Comparator.comparing(FeeRule::bookId).thenComparing(FeeRule::effectiveDate);

    private FeeRules() {
    }

    /** {@code BOOK_ID = :book AND EFF_DT <= :date AND EXP_DT > :date} (half-open window). */
    public static boolean covers(FeeRule rule, String bookId, LocalDate businessDate) {
        return rule.bookId().stripTrailing().equals(bookId.stripTrailing())
                && !rule.effectiveDate().isAfter(businessDate)
                && rule.expiryDate().isAfter(businessDate);
    }

    public static Optional<FeeRule> effective(List<FeeRule> rules, String bookId, LocalDate businessDate) {
        return rules.stream()
                .filter(rule -> covers(rule, bookId, businessDate))
                .min(DUMP_ORDER);
    }

    public static List<FeeRule> ordered(List<FeeRule> rules) {
        return rules.stream()
                .map(rule -> new FeeRule(rule.bookId().stripTrailing(), rule.feePct(), rule.feeCap(),
                        rule.effectiveDate(), rule.expiryDate()))
                .sorted(DUMP_ORDER)
                .toList();
    }
}
