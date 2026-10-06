package com.carddemo.observability;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * SYSOUT counters DISPLAYed by the legacy chain, mapped to Micrometer metric names.
 * Must stay in sync with ops/observability/counter-catalog.json.
 */
public enum LegacyCounter {
    RECORDS_READ(ChainStep.STEP010, "RECORDS READ", Kind.COUNT, "xfer.extract.records.read"),
    TRANSFERS_SELECTED(ChainStep.STEP010, "TRANSFERS SELECTED", Kind.COUNT, "xfer.extract.transfers.selected"),
    UNMATCHED_CARDS(ChainStep.STEP010, "UNMATCHED CARDS", Kind.COUNT, "xfer.extract.cards.unmatched"),
    TRANSFERS_POSTED(ChainStep.STEP020, "TRANSFERS POSTED", Kind.COUNT, "xfer.posting.transfers.posted"),
    TOTAL_FEES(ChainStep.STEP020, "TOTAL FEES", Kind.AMOUNT, "xfer.posting.fees.amount"),
    GRAND_TOTAL_FEE(ChainStep.STEP030, "GRAND TOTAL FEE", Kind.AMOUNT, "xfer.recon.fees.grand_total");

    /** COUNT is PIC 9(09); AMOUNT is PIC S9(09)V99. */
    public enum Kind { COUNT, AMOUNT }

    private static final BigDecimal NINE_DIGIT_MODULUS = BigDecimal.TEN.pow(9);

    private final ChainStep step;
    private final String label;
    private final Kind kind;
    private final String metric;

    LegacyCounter(ChainStep step, String label, Kind kind, String metric) {
        this.step = step;
        this.label = label;
        this.kind = kind;
        this.metric = metric;
    }

    public ChainStep step() {
        return step;
    }

    public String label() {
        return label;
    }

    public Kind kind() {
        return kind;
    }

    public String metric() {
        return metric;
    }

    public String program() {
        return step.program();
    }

    /** Applies the high-order truncation the receiving PIC clause would. */
    public BigDecimal truncate(BigDecimal value) {
        return value.remainder(NINE_DIGIT_MODULUS);
    }

    public static List<LegacyCounter> forStep(ChainStep step) {
        return Arrays.stream(values()).filter(c -> c.step == step).toList();
    }
}
