package com.carddemo.xferfee.observability;

import com.carddemo.xferfee.contracts.RejectReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Micrometer meters mirroring the SYSOUT counters and return codes of the xferfee chain. */
public class XferMetrics {

    public static final String PREFIX = "carddemo.xferfee";
    public static final String RECORDS_READ = PREFIX + ".extract.records.read";
    public static final String TRANSFERS_SELECTED = PREFIX + ".extract.transfers.selected";
    public static final String UNMATCHED_CARDS = PREFIX + ".extract.unmatched.cards";
    public static final String TRANSFERS_POSTED = PREFIX + ".posting.transfers.posted";
    public static final String TOTAL_FEES = PREFIX + ".posting.fees.total";
    public static final String RECON_GRAND_TOTAL_FEE = PREFIX + ".recon.grand.total.fee";
    public static final String RECON_FEE_RECORDS = PREFIX + ".recon.fee.records";
    public static final String TRANSFERS_REJECTED = PREFIX + ".transfers.rejected";
    public static final String STEP_RUNS = PREFIX + ".step.runs";
    public static final String STEP_WARNINGS = PREFIX + ".step.warnings";
    public static final String STEP_ALERTS = PREFIX + ".step.alerts";
    public static final String STEP_RETURN_CODE = PREFIX + ".step.return.code";
    public static final String RUN_MAXCC = PREFIX + ".run.maxcc";
    public static final String DLQ_ENTRIES = PREFIX + ".dlq.entries";

    private final MeterRegistry registry;
    private final Counter recordsRead;
    private final Counter transfersSelected;
    private final Counter unmatchedCards;
    private final Counter transfersPosted;
    private final Counter totalFees;
    private final Counter reconGrandTotalFee;
    private final Counter reconFeeRecords;
    private final Map<ChainStep, AtomicInteger> lastReturnCode = new EnumMap<>(ChainStep.class);
    private final AtomicInteger maxcc = new AtomicInteger();

    public XferMetrics(MeterRegistry registry) {
        this.registry = registry;
        recordsRead = counter(RECORDS_READ, ChainStep.STEP010, "DALYTRAN records read (CBXFR01C RECORDS READ)");
        transfersSelected = counter(TRANSFERS_SELECTED, ChainStep.STEP010,
                "Type-08 transfers written to XFER.EXTRACT (CBXFR01C TRANSFERS SELECTED)");
        unmatchedCards = counter(UNMATCHED_CARDS, ChainStep.STEP010,
                "Transfers whose card or account did not resolve (CBXFR01C UNMATCHED CARDS)");
        transfersPosted = counter(TRANSFERS_POSTED, ChainStep.STEP020,
                "Transfers posted to the ledger (XFERFEE TRANSFERS POSTED)");
        totalFees = Counter.builder(TOTAL_FEES)
                .description("Fees posted (XFERFEE TOTAL FEES)")
                .baseUnit("currency")
                .tag("step", ChainStep.STEP020.name())
                .tag("program", ChainStep.STEP020.program())
                .register(registry);
        reconGrandTotalFee = Counter.builder(RECON_GRAND_TOTAL_FEE)
                .description("Reconciled fee grand total (CBXFR03C GRAND TOTAL FEE)")
                .baseUnit("currency")
                .tag("step", ChainStep.STEP030.name())
                .tag("program", ChainStep.STEP030.program())
                .register(registry);
        reconFeeRecords = counter(RECON_FEE_RECORDS, ChainStep.STEP030,
                "Fee records reconciled (XFER.RECON.RPT GRAND TOTAL COUNT)");
        for (ChainStep step : ChainStep.values()) {
            AtomicInteger holder = new AtomicInteger();
            lastReturnCode.put(step, holder);
            Gauge.builder(STEP_RETURN_CODE, holder, AtomicInteger::get)
                    .description("Return code of the step's last run")
                    .tag("step", step.name())
                    .tag("program", step.program())
                    .register(registry);
        }
        Gauge.builder(RUN_MAXCC, maxcc, AtomicInteger::get)
                .description("MAXCC of the last XFRDAILY run")
                .register(registry);
    }

    private Counter counter(String name, ChainStep step, String description) {
        return Counter.builder(name)
                .description(description)
                .tag("step", step.name())
                .tag("program", step.program())
                .register(registry);
    }

    public void recordsRead(long count) {
        recordsRead.increment(count);
    }

    public void transfersSelected(long count) {
        transfersSelected.increment(count);
    }

    public void transferPosted(BigDecimal fee) {
        transfersPosted.increment();
        totalFees.increment(fee.doubleValue());
    }

    public void reconTotals(long count, BigDecimal grandTotalFee) {
        reconFeeRecords.increment(count);
        reconGrandTotalFee.increment(grandTotalFee.doubleValue());
    }

    public void transferRejected(ChainStep step, RejectReason reason) {
        if (step == ChainStep.STEP010) {
            unmatchedCards.increment();
        }
        Counter.builder(TRANSFERS_REJECTED)
                .description("TransferRejected events by reason")
                .tag("step", step.name())
                .tag("program", step.program())
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    public void stepCompleted(ChainStep step, int returnCode) {
        lastReturnCode.get(step).set(returnCode);
        Severity severity = ReturnCodePolicy.classify(returnCode);
        Counter.builder(STEP_RUNS)
                .description("Step executions by return code")
                .tag("step", step.name())
                .tag("program", step.program())
                .tag("rc", Integer.toString(returnCode))
                .tag("severity", severity.name())
                .register(registry)
                .increment();
        if (severity == Severity.WARNING) {
            stepCounter(STEP_WARNINGS, "Steps ending RC 4 (warning)", step, returnCode).increment();
        } else if (severity == Severity.ALERT) {
            stepCounter(STEP_ALERTS, "Steps ending RC 8+ (alert)", step, returnCode).increment();
        }
    }

    public void runMaxcc(int value) {
        maxcc.set(value);
    }

    public void deadLettered(ChainStep step, int returnCode) {
        stepCounter(DLQ_ENTRIES, "Dead-letter entries written for RC 8+ steps", step, returnCode).increment();
    }

    private Counter stepCounter(String name, String description, ChainStep step, int returnCode) {
        return Counter.builder(name)
                .description(description)
                .tag("step", step.name())
                .tag("program", step.program())
                .tag("rc", Integer.toString(returnCode))
                .register(registry);
    }
}
