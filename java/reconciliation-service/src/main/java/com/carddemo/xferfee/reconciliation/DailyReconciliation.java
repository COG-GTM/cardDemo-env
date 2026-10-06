package com.carddemo.xferfee.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * True per-book totals for a business date. Unlike the legacy report, each book appears once no
 * matter how its transfers were interleaved in the input.
 */
public record DailyReconciliation(
        LocalDate businessDate,
        DayStatus status,
        Integer postingRc,
        Integer legacyRc,
        List<BookTotal> books,
        BookTotal grandTotal,
        Rejected rejected) {

    public record Rejected(long count, Map<String, Long> byReason) {
    }
}
