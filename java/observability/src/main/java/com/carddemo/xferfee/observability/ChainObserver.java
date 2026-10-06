package com.carddemo.xferfee.observability;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the step results of the {@code contracts} SPIs into the signals operators get from SYSOUT
 * and the job log today: the COBOL counters (Micrometer meters plus SYSOUT-equivalent values),
 * return codes, {@link TransferRejected} counts and, for RC 8+, an alert and a dead-letter entry.
 *
 * <p>Call {@link #startRun}, then one method per step in chain order, then {@link #report}.
 * Counters follow the COBOL DISPLAYs: CBXFR01C always prints its three counters; XFERFEE prints
 * posted/total fees only when it does not abend; CBXFR03C prints the grand total only when it read
 * at least one fee record (otherwise {@code NO FEE RECORDS}, RC 4).
 */
public class ChainObserver {

    public static final String CHAIN = "xferfee";

    private final XferMetrics metrics;
    private final AlertPublisher alerts;
    private final DeadLetterQueue deadLetters;
    private final Clock clock;
    private final Map<ChainStep, ObservedStep> steps = new EnumMap<>(ChainStep.class);
    private String runDate = "";

    public ChainObserver(XferMetrics metrics, AlertPublisher alerts, DeadLetterQueue deadLetters, Clock clock) {
        this.metrics = metrics;
        this.alerts = alerts;
        this.deadLetters = deadLetters;
        this.clock = clock;
    }

    public synchronized void startRun(String runDate) {
        steps.clear();
        this.runDate = runDate == null ? "" : runDate;
    }

    /** STEP010 / CBXFR01C. */
    public synchronized void intake(List<DailyTransaction> dailyTransactions, TransferIntake.IntakeResult result) {
        long read = dailyTransactions.size();
        long selected = result.requested().size();
        long unmatched = result.rejected().size();
        metrics.recordsRead(read);
        metrics.transfersSelected(selected);
        List<CounterValue> counters = List.of(
                count("RECORDS READ", XferMetrics.RECORDS_READ, read),
                count("TRANSFERS SELECTED", XferMetrics.TRANSFERS_SELECTED, selected),
                count("UNMATCHED CARDS", XferMetrics.UNMATCHED_CARDS, unmatched));
        complete(ChainStep.STEP010, result.report(), counters, result.rejected());
    }

    /** STEP020 / XFERFEE. On RC 8+ nothing was committed (BR-15), so no posted counters are kept. */
    public synchronized void posting(AccountPosting.PostingResult result) {
        int rc = result.report().returnCode();
        List<CounterValue> counters = List.of();
        if (ReturnCodePolicy.classify(rc) != Severity.ALERT) {
            BigDecimal total = BigDecimal.ZERO;
            for (TransferPosted posted : result.posted()) {
                metrics.transferPosted(posted.feeAmount());
                total = total.add(posted.feeAmount());
            }
            counters = List.of(
                    count("TRANSFERS POSTED", XferMetrics.TRANSFERS_POSTED, result.posted().size()),
                    money("TOTAL FEES", XferMetrics.TOTAL_FEES, total));
        }
        complete(ChainStep.STEP020, result.report(), counters, result.rejected());
    }

    /** STEP030 / CBXFR03C over the fee records XFERFEE wrote. */
    public synchronized void reconciliation(List<TransferPosted> feeRecords, Reconciliation.ReconResult result) {
        List<CounterValue> counters = List.of();
        if (!feeRecords.isEmpty()) {
            BigDecimal grand = BigDecimal.ZERO;
            for (TransferPosted posted : feeRecords) {
                grand = grand.add(posted.feeAmount());
            }
            metrics.reconTotals(feeRecords.size(), grand);
            counters = List.of(money("GRAND TOTAL FEE", XferMetrics.RECON_GRAND_TOTAL_FEE, grand));
        }
        complete(ChainStep.STEP030, result.report(), counters, List.of());
    }

    /** A step not run because of its JCL {@code COND} (STEP030 after STEP020 RC 8+). */
    public synchronized void skipped(ChainStep step) {
        steps.put(step, new ObservedStep(step, step.program(), false, 0, Severity.OK, List.of(), List.of(),
                List.of()));
    }

    public synchronized ChainRunReport report() {
        return new ChainRunReport(CHAIN, runDate, List.copyOf(steps.values()), maxcc());
    }

    private void complete(ChainStep step, StepReport report, List<CounterValue> counters,
            List<TransferRejected> rejected) {
        int rc = report.returnCode();
        Severity severity = ReturnCodePolicy.classify(rc);
        List<String> sysout = report.sysout() == null ? List.of() : List.copyOf(report.sysout());
        for (TransferRejected transfer : rejected) {
            metrics.transferRejected(step, transfer.reason());
        }
        ObservedStep observed = new ObservedStep(step, step.program(), true, rc, severity, counters,
                List.copyOf(rejected), sysout);
        steps.put(step, observed);
        metrics.stepCompleted(step, rc);
        metrics.runMaxcc(maxcc());
        if (severity == Severity.ALERT) {
            raise(observed);
        }
    }

    private void raise(ObservedStep step) {
        String reason = step.rejected().isEmpty()
                ? step.program() + " ended RC " + step.returnCode()
                : step.rejected().get(0).reason().name();
        String summary = step.program() + " abended RC " + step.returnCode()
                + (step.sysout().isEmpty() ? "" : ": " + step.sysout().get(0));
        alerts.publish(new ChainAlert(CHAIN, step.step(), step.program(), step.returnCode(), Severity.ALERT,
                summary, step.sysout(), clock.instant()));
        deadLetters.put(new DeadLetterEntry(CHAIN, runDate, step.step(), step.program(), step.returnCode(),
                reason, step.rejected(), step.sysout(), clock.instant()));
        metrics.deadLettered(step.step(), step.returnCode());
    }

    private int maxcc() {
        int max = 0;
        for (ObservedStep step : steps.values()) {
            max = Math.max(max, step.returnCode());
        }
        return max;
    }

    private static CounterValue count(String label, String metric, long value) {
        return new CounterValue(label, metric, Long.toString(value), SysoutFormat.count(value));
    }

    private static CounterValue money(String label, String metric, BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
        return new CounterValue(label, metric, scaled.toPlainString(), SysoutFormat.signedMoney(scaled));
    }

    /** Counters as {@code "<PROGRAM>: <LABEL> <display>"}, the exact DISPLAY lines COBOL writes. */
    public static List<String> counterLines(ObservedStep step) {
        List<String> lines = new ArrayList<>();
        for (CounterValue counter : step.counters()) {
            lines.add(step.program() + ": " + counter.label() + " " + counter.display());
        }
        return lines;
    }
}
