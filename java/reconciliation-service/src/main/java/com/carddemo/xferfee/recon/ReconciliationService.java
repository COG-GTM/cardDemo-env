package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Consumes posting outcomes per business date and produces reconciliation views. */
@Service
public class ReconciliationService {

    private final Map<LocalDate, BusinessDay> days = new ConcurrentHashMap<>();
    private final LegacyReconStep legacyStep = new LegacyReconStep();

    public void onPosted(TransferPosted posted) {
        day(posted.businessDate()).posted(posted);
    }

    public void onRejected(TransferRejected rejected) {
        day(rejected.businessDate()).rejected(rejected);
    }

    /** True per-book totals; unlike the legacy report, each book appears once. */
    public Optional<ReconSummary> summary(LocalDate businessDate) {
        BusinessDay day = days.get(businessDate);
        if (day == null) {
            return Optional.empty();
        }
        List<TransferPosted> posted = day.postedSnapshot();
        Map<String, BookTotal> books = new TreeMap<>();
        for (TransferPosted transfer : posted) {
            String bookId = transfer.bookId().strip();
            books.merge(bookId, total(bookId, transfer), ReconciliationService::add);
        }
        BookTotal grand = books.values().stream()
                .reduce(new BookTotal("ALL", 0, money(BigDecimal.ZERO), money(BigDecimal.ZERO)),
                        ReconciliationService::add);
        return Optional.of(new ReconSummary(businessDate, List.copyOf(books.values()),
                new BookTotal("ALL", grand.count(), grand.amount(), grand.fee()),
                day.rejectedCount()));
    }

    /** STEP030 output for the day in arrival (file) order; empty when bypassed by COND. */
    public Optional<LegacyReconReport> legacyReport(LocalDate businessDate, int postingReturnCode) {
        BusinessDay day = days.get(businessDate);
        List<TransferPosted> posted = day == null ? List.of() : day.postedSnapshot();
        return legacyStep.run(posted, postingReturnCode);
    }

    private BusinessDay day(LocalDate businessDate) {
        return days.computeIfAbsent(businessDate, ignored -> new BusinessDay());
    }

    private static BookTotal total(String bookId, TransferPosted transfer) {
        return new BookTotal(bookId, 1, money(transfer.tranAmt()), money(transfer.feeAmt()));
    }

    private static BookTotal add(BookTotal left, BookTotal right) {
        return new BookTotal(left.bookId(), left.count() + right.count(),
                left.amount().add(right.amount()), left.fee().add(right.fee()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2);
    }

    private static final class BusinessDay {
        private final List<TransferPosted> posted = new ArrayList<>();
        private long rejected;

        synchronized void posted(TransferPosted transfer) {
            posted.add(transfer);
        }

        synchronized void rejected(TransferRejected transfer) {
            rejected++;
        }

        synchronized List<TransferPosted> postedSnapshot() {
            return List.copyOf(posted);
        }

        synchronized long rejectedCount() {
            return rejected;
        }
    }
}
