package com.carddemo.xferfee.reconciliation;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.reconciliation.ReconciliationException.Kind;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReconRenderer;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Mutable state for one business date. Posted fees keep arrival (file) order. */
final class BusinessDay {

    private static final LegacyReconRenderer RENDERER = new LegacyReconRenderer();

    private final LocalDate businessDate;
    private final LinkedHashMap<String, TransferPosted> posted = new LinkedHashMap<>();
    private final LinkedHashMap<String, TransferRejected> rejected = new LinkedHashMap<>();
    private DayStatus status = DayStatus.OPEN;
    private Integer postingRc;
    private LegacyReport report;

    BusinessDay(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    /** Identical redeliveries are acknowledged even after close; a different payload is a conflict. */
    synchronized void addPosted(TransferPosted event) {
        TransferPosted existing = posted.get(event.tranId());
        if (existing != null) {
            requireSame(samePosted(existing, event), event.tranId());
            return;
        }
        requireOpen();
        posted.put(event.tranId(), event);
    }

    synchronized void addRejected(TransferRejected reject) {
        TransferRejected existing = rejected.get(reject.tranId());
        if (existing != null) {
            requireSame(existing.equals(reject), reject.tranId());
            return;
        }
        requireOpen();
        rejected.put(reject.tranId(), reject);
    }

    synchronized void close(int postingRc) {
        requireOpen();
        this.postingRc = postingRc;
        if (postingRc > 4) {
            status = DayStatus.SKIPPED;
            return;
        }
        report = RENDERER.render(posted.values().stream().map(FeeLine::of).toList());
        status = report.returnCode() == 0 ? DayStatus.CLOSED : DayStatus.NO_FEES;
    }

    synchronized Optional<LegacyReport> legacyReport() {
        if (status == DayStatus.OPEN) {
            throw new ReconciliationException(Kind.DAY_OPEN, businessDate + " is not closed");
        }
        return Optional.ofNullable(report);
    }

    synchronized DailyReconciliation summary() {
        Map<String, BookTotal> books = new TreeMap<>();
        BookTotal grand = BookTotal.empty("*");
        for (FeeLine line : posted.values().stream().map(FeeLine::of).toList()) {
            String book = line.bookId().strip();
            books.computeIfAbsent(book, BookTotal::empty);
            books.compute(book, (key, total) -> total.plus(line));
            grand = grand.plus(line);
        }
        Map<String, Long> byReason = new TreeMap<>();
        rejected.values().forEach(r -> byReason.merge(r.reason() == null ? "UNKNOWN" : r.reason().name(), 1L, Long::sum));
        List<BookTotal> bookList = new ArrayList<>(books.values());
        bookList.sort(Comparator.comparing(BookTotal::bookId));
        return new DailyReconciliation(businessDate, status, postingRc,
                report == null ? null : report.returnCode(), List.copyOf(bookList), grand,
                new DailyReconciliation.Rejected(rejected.size(), byReason));
    }

    private void requireOpen() {
        if (status != DayStatus.OPEN) {
            throw new ReconciliationException(Kind.DAY_CLOSED, businessDate + " is already closed");
        }
    }

    private static void requireSame(boolean same, String tranId) {
        if (!same) {
            throw new ReconciliationException(Kind.CONFLICTING_DUPLICATE,
                    "transfer " + tranId + " already received with a different payload");
        }
    }

    private static boolean samePosted(TransferPosted a, TransferPosted b) {
        return a.tranId().equals(b.tranId()) && Objects.equals(a.tranDate(), b.tranDate())
                && a.sourceAccountId() == b.sourceAccountId() && a.targetAccountId() == b.targetAccountId()
                && Objects.equals(a.bookId(), b.bookId()) && sameMoney(a.amount(), b.amount())
                && sameMoney(a.feePct(), b.feePct()) && sameMoney(a.feeAmount(), b.feeAmount())
                && a.capApplied() == b.capApplied() && Objects.equals(a.ruleEffectiveDate(), b.ruleEffectiveDate());
    }

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
