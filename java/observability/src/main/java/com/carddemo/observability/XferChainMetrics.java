package com.carddemo.observability;

import com.carddemo.contracts.StepReturnCode;
import com.carddemo.contracts.TransferRejected;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes legacy SYSOUT counters and step return codes as metrics, and applies the
 * return-code policy: RC 4 raises a warning alert, RC 8 raises a critical alert and
 * dead-letters the in-flight work.
 */
public final class XferChainMetrics {

    public static final String STEP_RETURN_CODE = "xfer.step.return_code";
    public static final String STEP_RUNS = "xfer.step.runs";
    public static final String TRANSFERS_REJECTED = "xfer.transfers.rejected";
    public static final String ALERTS = "xfer.alerts";
    public static final String DLQ_ENTRIES = "xfer.dlq.entries";

    private final MeterRegistry registry;
    private final Tags commonTags;
    private final Map<ChainStep, AtomicInteger> returnCodes = new EnumMap<>(ChainStep.class);
    private final Map<LegacyCounter, BigDecimal> totals = new EnumMap<>(LegacyCounter.class);
    private final List<TransferRejected> rejects = new ArrayList<>();
    private final List<DeadLetterEntry> deadLetters = new ArrayList<>();
    private final List<Alert> alerts = new ArrayList<>();

    public XferChainMetrics(MeterRegistry registry, Tags commonTags) {
        this.registry = registry;
        this.commonTags = Tags.of("chain", "xferfee").and(commonTags);
        for (ChainStep step : ChainStep.values()) {
            AtomicInteger rc = registry.gauge(STEP_RETURN_CODE, stepTags(step), new AtomicInteger(0));
            returnCodes.put(step, rc);
        }
        for (LegacyCounter counter : LegacyCounter.values()) {
            totals.put(counter, BigDecimal.ZERO);
            registry.counter(counter.metric(), stepTags(counter.step()));
        }
    }

    public void record(StepOutcome outcome) {
        ChainStep step = outcome.step();
        StepReturnCode rc = StepReturnCode.of(outcome.returnCode());
        if (outcome.countersDisplayed()) {
            outcome.counters().asMap().forEach((counter, value) -> {
                totals.merge(counter, value, BigDecimal::add);
                registry.counter(counter.metric(), stepTags(step)).increment(value.doubleValue());
            });
        }
        returnCodes.get(step).set(outcome.returnCode());
        registry.counter(STEP_RUNS, stepTags(step).and("outcome", rc.outcome())).increment();

        for (TransferRejected reject : outcome.rejects()) {
            rejects.add(reject);
            registry.counter(TRANSFERS_REJECTED, stepTags(step).and("reason", reject.reason().name()))
                    .increment();
        }

        List<DeadLetterEntry> parked = new ArrayList<>(outcome.deadLetters());
        if (rc == StepReturnCode.ABEND && parked.isEmpty()) {
            parked.add(new DeadLetterEntry(step.name(), step.program(), outcome.returnCode(),
                    "STEP_ABEND", null, Map.of()));
        }
        for (DeadLetterEntry entry : parked) {
            deadLetters.add(entry);
            registry.counter(DLQ_ENTRIES, stepTags(step).and("reason", entry.reason())).increment();
        }

        if (rc != StepReturnCode.OK) {
            String summary = outcome.messages().isEmpty()
                    ? step.program() + " ended RC " + outcome.returnCode()
                    : outcome.messages().get(0).strip();
            alerts.add(new Alert(rc.alertSeverity(), step.name(), step.program(),
                    outcome.returnCode(), summary));
            registry.counter(ALERTS, stepTags(step).and("severity", rc.alertSeverity())).increment();
        }
    }

    public BigDecimal total(LegacyCounter counter) {
        return totals.get(counter);
    }

    public List<TransferRejected> rejects() {
        return Collections.unmodifiableList(rejects);
    }

    public List<DeadLetterEntry> deadLetters() {
        return Collections.unmodifiableList(deadLetters);
    }

    public List<Alert> alerts() {
        return Collections.unmodifiableList(alerts);
    }

    public MeterRegistry registry() {
        return registry;
    }

    private Tags stepTags(ChainStep step) {
        return commonTags.and("step", step.name(), "program", step.program());
    }
}
