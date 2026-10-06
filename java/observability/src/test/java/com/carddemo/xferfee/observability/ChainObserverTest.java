package com.carddemo.xferfee.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainObserverTest {

    private static final LocalDate DAY = LocalDate.of(2024, 6, 30);

    private SimpleMeterRegistry registry;
    private RecordingAlertPublisher alerts;
    private InMemoryDeadLetterQueue dlq;
    private ChainObserver observer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        alerts = new RecordingAlertPublisher(alert -> { });
        dlq = new InMemoryDeadLetterQueue();
        observer = new ChainObserver(new XferMetrics(registry), alerts, dlq,
                Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC));
        observer.startRun("2024-06-30");
    }

    @Test
    void cleanRunMirrorsCobolCountersAsMetricsAndSysoutValues() {
        List<DailyTransaction> daily = List.of(daily("T1", "08"), daily("T2", "01"), daily("T3", "08"));
        TransferRequested first = requested("T1", "100.00");
        TransferRequested second = requested("T3", "420.00");
        observer.intake(daily, new TransferIntake.IntakeResult(List.of(first, second), List.of(),
                new StepReport("STEP010", 0, List.of())));
        List<TransferPosted> posted = List.of(posted(first, "1.25"), posted(second, "5.25"));
        observer.posting(new AccountPosting.PostingResult(posted, List.of(), List.of(), List.of(),
                new StepReport("STEP020", 0, List.of())));
        observer.reconciliation(posted, new Reconciliation.ReconResult(List.of(),
                new StepReport("STEP030", 0, List.of())));

        ChainRunReport report = observer.report();
        assertThat(report.maxcc()).isZero();
        assertThat(ChainObserver.counterLines(report.step(ChainStep.STEP010).orElseThrow())).containsExactly(
                "CBXFR01C: RECORDS READ 000000003",
                "CBXFR01C: TRANSFERS SELECTED 000000002",
                "CBXFR01C: UNMATCHED CARDS 000000000");
        assertThat(ChainObserver.counterLines(report.step(ChainStep.STEP020).orElseThrow())).containsExactly(
                "XFERFEE: TRANSFERS POSTED 000000002",
                "XFERFEE: TOTAL FEES +00000000650");
        assertThat(ChainObserver.counterLines(report.step(ChainStep.STEP030).orElseThrow())).containsExactly(
                "CBXFR03C: GRAND TOTAL FEE +00000000650");

        assertThat(count(XferMetrics.RECORDS_READ)).isEqualTo(3.0);
        assertThat(count(XferMetrics.TRANSFERS_SELECTED)).isEqualTo(2.0);
        assertThat(count(XferMetrics.UNMATCHED_CARDS)).isZero();
        assertThat(count(XferMetrics.TRANSFERS_POSTED)).isEqualTo(2.0);
        assertThat(count(XferMetrics.TOTAL_FEES)).isEqualTo(6.5);
        assertThat(count(XferMetrics.RECON_GRAND_TOTAL_FEE)).isEqualTo(6.5);
        assertThat(registry.find(XferMetrics.STEP_WARNINGS).counters()).isEmpty();
        assertThat(alerts.alerts()).isEmpty();
        assertThat(dlq.entries()).isEmpty();
    }

    @Test
    void rc4CountsWarningAndTransferRejectedButRaisesNoAlert() {
        TransferRejected unmatched = new TransferRejected("T2", "9999000011112222", RejectReason.UNMATCHED_CARD,
                "9999000011112222");
        observer.intake(List.of(daily("T1", "08"), daily("T2", "08")), new TransferIntake.IntakeResult(
                List.of(requested("T1", "10.00")), List.of(unmatched),
                new StepReport("STEP010", 4, List.of("CBXFR01C: CARD NOT FOUND 9999000011112222"))));

        ObservedStep step = observer.report().step(ChainStep.STEP010).orElseThrow();
        assertThat(step.severity()).isEqualTo(Severity.WARNING);
        assertThat(step.rejected()).containsExactly(unmatched);
        assertThat(ChainObserver.counterLines(step)).contains("CBXFR01C: UNMATCHED CARDS 000000001");
        assertThat(count(XferMetrics.UNMATCHED_CARDS)).isEqualTo(1.0);
        assertThat(registry.get(XferMetrics.TRANSFERS_REJECTED).tag("reason", "UNMATCHED_CARD").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(XferMetrics.STEP_WARNINGS).tag("step", "STEP010").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(XferMetrics.STEP_RETURN_CODE).tag("step", "STEP010").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(XferMetrics.RUN_MAXCC).gauge().value()).isEqualTo(4.0);
        assertThat(alerts.alerts()).isEmpty();
        assertThat(dlq.entries()).isEmpty();
    }

    @Test
    void rc8RaisesAlertAndDeadLetterAndKeepsNoPostedCounters() {
        TransferRequested request = requested("T1", "10.00");
        observer.intake(List.of(daily("T1", "08")), new TransferIntake.IntakeResult(List.of(request), List.of(),
                new StepReport("STEP010", 0, List.of())));
        TransferRejected noRule = new TransferRejected("T1", request.cardNumber(), RejectReason.NO_FEE_RULE, "RETAIL");
        List<String> sysout = List.of("XFERFEE: NO FEE RULE FOR BOOK RETAIL    ", "XFERFEE: 9999-ABEND-PROGRAM");
        observer.posting(new AccountPosting.PostingResult(List.of(), List.of(), List.of(), List.of(noRule),
                new StepReport("STEP020", 8, sysout)));
        observer.skipped(ChainStep.STEP030);

        ChainRunReport report = observer.report();
        assertThat(report.maxcc()).isEqualTo(8);
        ObservedStep posting = report.step(ChainStep.STEP020).orElseThrow();
        assertThat(posting.severity()).isEqualTo(Severity.ALERT);
        assertThat(posting.counters()).isEmpty();
        assertThat(posting.sysout()).isEqualTo(sysout);
        assertThat(report.step(ChainStep.STEP030).orElseThrow().executed()).isFalse();

        assertThat(alerts.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.step()).isEqualTo(ChainStep.STEP020);
            assertThat(alert.returnCode()).isEqualTo(8);
            assertThat(alert.summary()).isEqualTo("XFERFEE abended RC 8: XFERFEE: NO FEE RULE FOR BOOK RETAIL    ");
        });
        assertThat(dlq.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.runDate()).isEqualTo("2024-06-30");
            assertThat(entry.reason()).isEqualTo("NO_FEE_RULE");
            assertThat(entry.rejected()).containsExactly(noRule);
            assertThat(entry.sysout()).isEqualTo(sysout);
        });
        assertThat(registry.get(XferMetrics.STEP_ALERTS).tag("step", "STEP020").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(XferMetrics.DLQ_ENTRIES).tag("step", "STEP020").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(XferMetrics.TRANSFERS_REJECTED).tag("reason", "NO_FEE_RULE").counter().count())
                .isEqualTo(1.0);
        assertThat(count(XferMetrics.TRANSFERS_POSTED)).isZero();
    }

    @Test
    void emptyFeeSetIsRc4WithoutGrandTotal() {
        observer.intake(List.of(), new TransferIntake.IntakeResult(List.of(), List.of(),
                new StepReport("STEP010", 0, List.of())));
        observer.posting(new AccountPosting.PostingResult(List.of(), List.of(), List.of(), List.of(),
                new StepReport("STEP020", 0, List.of())));
        observer.reconciliation(List.of(), new Reconciliation.ReconResult(List.of(),
                new StepReport("STEP030", 4, List.of("CBXFR03C: NO FEE RECORDS"))));

        ObservedStep recon = observer.report().step(ChainStep.STEP030).orElseThrow();
        assertThat(recon.severity()).isEqualTo(Severity.WARNING);
        assertThat(recon.counters()).isEmpty();
        assertThat(ChainObserver.counterLines(observer.report().step(ChainStep.STEP020).orElseThrow()))
                .containsExactly("XFERFEE: TRANSFERS POSTED 000000000", "XFERFEE: TOTAL FEES +00000000000");
        assertThat(alerts.alerts()).isEmpty();
    }

    @Test
    void startRunResetsSteps() {
        observer.skipped(ChainStep.STEP030);
        observer.startRun("2024-07-01");
        assertThat(observer.report().steps()).isEmpty();
        assertThat(observer.report().runDate()).isEqualTo("2024-07-01");
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private static DailyTransaction daily(String id, String type) {
        return new DailyTransaction(id, type, 1, "DEMO", "", new BigDecimal("1.00"), 1L, "", "", "",
                "1000000000000001", "2024-06-30 10:00:00.000000", "2024-06-30 10:01:00.000000");
    }

    private static TransferRequested requested(String id, String amount) {
        return new TransferRequested(id, DAY, 1L, 2L, "RETAIL", new BigDecimal(amount), "1000000000000001");
    }

    private static TransferPosted posted(TransferRequested request, String fee) {
        return new TransferPosted(request.tranId(), DAY, 1L, 2L, "RETAIL", request.amount(),
                new BigDecimal("0.012500"), new BigDecimal(fee), false, LocalDate.of(2024, 6, 15));
    }
}
