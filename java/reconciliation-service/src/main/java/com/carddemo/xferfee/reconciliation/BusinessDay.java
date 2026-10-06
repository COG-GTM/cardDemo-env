package com.carddemo.xferfee.reconciliation;

import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.reconciliation.ReconciliationException.Kind;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReconRenderer;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Mutable state for one business date. Posted fees keep arrival (file) order. */
final class BusinessDay {

    private static final LegacyReconRenderer RENDERER = new LegacyReconRenderer();

    private final LocalDate businessDate;
    private final LinkedHashMap<String, FeeLine> posted = new LinkedHashMap<>();
    private final LinkedHashMap<String, TransferRejected> rejected = new LinkedHashMap<>();
    private DayStatus status = DayStatus.OPEN;
    private Integer postingRc;
    private LegacyReport report;

    BusinessDay(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    synchronized void addPosted(FeeLine line) {
        requireOpen();
        FeeLine existing = posted.putIfAbsent(line.tranId(), line);
        if (existing != null && !sameFee(existing, line)) {
            throw new ReconciliationException(Kind.CONFLICTING_DUPLICATE,
                    "transfer " + line.tranId() + " already posted with a different payload");
        }
    }

    synchronized void addRejected(TransferRejected reject) {
        requireOpen();
        rejected.putIfAbsent(reject.tranId(), reject);
    }

    synchronized void close(int postingRc) {
        requireOpen();
        this.postingRc = postingRc;
        if (postingRc > 4) {
            status = DayStatus.SKIPPED;
            return;
        }
        report = RENDERER.render(new ArrayList<>(posted.values()));
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
        for (FeeLine line : posted.values()) {
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

    private static boolean sameFee(FeeLine a, FeeLine b) {
        return a.tranDate().equals(b.tranDate()) && a.bookId().equals(b.bookId())
                && a.amount().compareTo(b.amount()) == 0 && a.fee().compareTo(b.fee()) == 0;
    }
}
