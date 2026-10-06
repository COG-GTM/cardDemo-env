package com.carddemo.observability;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Exact per-step counter values, mirroring the program's WORKING-STORAGE totals. */
public final class StepCounters {

    private final ChainStep step;
    private final EnumMap<LegacyCounter, BigDecimal> values = new EnumMap<>(LegacyCounter.class);

    public StepCounters(ChainStep step) {
        this.step = step;
        for (LegacyCounter counter : LegacyCounter.forStep(step)) {
            values.put(counter, BigDecimal.ZERO);
        }
    }

    public ChainStep step() {
        return step;
    }

    public void increment(LegacyCounter counter) {
        add(counter, BigDecimal.ONE);
    }

    public void add(LegacyCounter counter, BigDecimal amount) {
        if (counter.step() != step) {
            throw new IllegalArgumentException(counter + " is not emitted by " + step);
        }
        values.put(counter, counter.truncate(values.get(counter).add(amount)));
    }

    public BigDecimal get(LegacyCounter counter) {
        return values.get(counter);
    }

    public Map<LegacyCounter, BigDecimal> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
