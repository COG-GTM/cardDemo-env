package com.carddemo.xferfee.reconciliation;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.reconciliation.ReconciliationException.Kind;
import com.carddemo.xferfee.reconciliation.legacy.LegacyReport;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Replaces CBXFR03C. Consumes {@link TransferPosted} / {@link TransferRejected} per business date
 * and, at day close, freezes the day and renders the legacy XFER.RECON.RPT.
 */
@Service
public class ReconciliationService {

    private final Map<LocalDate, BusinessDay> days = new ConcurrentHashMap<>();

    public void onPosted(LocalDate businessDate, TransferPosted posted) {
        day(businessDate).addPosted(FeeLine.of(posted));
    }

    public void onRejected(LocalDate businessDate, TransferRejected rejected) {
        day(businessDate).addRejected(rejected);
    }

    /**
     * Close the business day. {@code postingRc} is the posting step's return code; above 4 the
     * report is skipped, mirroring {@code COND=(4,LT,STEP020)}.
     */
    public DailyReconciliation close(LocalDate businessDate, int postingRc) {
        BusinessDay day = day(businessDate);
        day.close(postingRc);
        return day.summary();
    }

    public Optional<DailyReconciliation> find(LocalDate businessDate) {
        return Optional.ofNullable(days.get(businessDate)).map(BusinessDay::summary);
    }

    /** Empty when the day was skipped; throws while the day is still open. */
    public Optional<LegacyReport> legacyReport(LocalDate businessDate) {
        BusinessDay day = days.get(businessDate);
        if (day == null) {
            throw new ReconciliationException(Kind.UNKNOWN_DAY, "no reconciliation for " + businessDate);
        }
        return day.legacyReport();
    }

    private BusinessDay day(LocalDate businessDate) {
        return days.computeIfAbsent(businessDate, BusinessDay::new);
    }
}
