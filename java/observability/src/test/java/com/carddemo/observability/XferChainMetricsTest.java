package com.carddemo.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.contracts.RejectReason;
import com.carddemo.contracts.TransferRejected;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class XferChainMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final XferChainMetrics metrics = new XferChainMetrics(registry, Tags.empty());

    @Test
    void cleanStepPublishesCountersWithoutAlerts() {
        StepCounters counters = new StepCounters(ChainStep.STEP020);
        counters.increment(LegacyCounter.TRANSFERS_POSTED);
        counters.add(LegacyCounter.TOTAL_FEES, new BigDecimal("6.50"));
        metrics.record(new StepOutcome(ChainStep.STEP020, 0, counters, true, List.of(), List.of(), List.of()));

        assertEquals(1.0, registry.get("xfer.posting.transfers.posted").counter().count());
        assertEquals(6.5, registry.get("xfer.posting.fees.amount").counter().count());
        assertEquals(0.0, registry.get("xfer.step.return_code").tag("step", "STEP020").gauge().value());
        assertEquals(1.0, registry.get("xfer.step.runs").tag("outcome", "ok").counter().count());
        assertTrue(metrics.alerts().isEmpty());
        assertTrue(metrics.deadLetters().isEmpty());
    }

    @Test
    void rc4RaisesWarningAndCountsRejects() {
        StepCounters counters = new StepCounters(ChainStep.STEP010);
        counters.increment(LegacyCounter.RECORDS_READ);
        counters.increment(LegacyCounter.UNMATCHED_CARDS);
        TransferRejected reject = new TransferRejected("T1", "4000", "STEP010", "CBXFR01C",
                RejectReason.CARD_NOT_FOUND, 4, "card 4000");
        metrics.record(new StepOutcome(ChainStep.STEP010, 4, counters, true,
                List.of("CBXFR01C: CARD NOT FOUND 4000"), List.of(reject), List.of()));

        assertEquals(4.0, registry.get("xfer.step.return_code").tag("step", "STEP010").gauge().value());
        assertEquals(1.0, registry.get("xfer.transfers.rejected").tag("reason", "CARD_NOT_FOUND").counter().count());
        assertEquals(1.0, registry.get("xfer.alerts").tag("severity", "warning").counter().count());
        assertEquals("CBXFR01C: CARD NOT FOUND 4000", metrics.alerts().get(0).summary());
        assertTrue(metrics.deadLetters().isEmpty());
    }

    @Test
    void rc8RaisesCriticalAlertDeadLettersAndSuppressesCounters() {
        StepCounters counters = new StepCounters(ChainStep.STEP020);
        counters.increment(LegacyCounter.TRANSFERS_POSTED);
        DeadLetterEntry entry = new DeadLetterEntry("STEP020", "XFERFEE", 8, "NO_FEE_RULE", "T2", Map.of());
        metrics.record(new StepOutcome(ChainStep.STEP020, 8, counters, false,
                List.of("XFERFEE: NO FEE RULE FOR BOOK RETAIL", "XFERFEE: 9999-ABEND-PROGRAM"),
                List.of(), List.of(entry)));

        assertEquals(0.0, registry.get("xfer.posting.transfers.posted").counter().count());
        assertEquals(8.0, registry.get("xfer.step.return_code").tag("step", "STEP020").gauge().value());
        assertEquals(1.0, registry.get("xfer.alerts").tag("severity", "critical").counter().count());
        assertEquals(1.0, registry.get("xfer.dlq.entries").tag("reason", "NO_FEE_RULE").counter().count());
        assertEquals(List.of(entry), metrics.deadLetters());
    }

    @Test
    void rc8WithoutPayloadStillDeadLettersTheStep() {
        metrics.record(new StepOutcome(ChainStep.STEP030, 12, new StepCounters(ChainStep.STEP030), false,
                List.of(), List.of(), List.of()));
        assertEquals("STEP_ABEND", metrics.deadLetters().get(0).reason());
        assertEquals("CBXFR03C ended RC 12", metrics.alerts().get(0).summary());
    }
}
