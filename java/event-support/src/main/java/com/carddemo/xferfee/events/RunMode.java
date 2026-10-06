package com.carddemo.xferfee.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** COG-1249 D1: how a run is committed. */
public enum RunMode {
    /** Legacy parity: the whole day commits or rolls back as one unit (XFERFEE single COMMIT). */
    BATCH_ATOMIC("batch-atomic"),
    /** Target default: one transaction per transfer, failures to the DLQ, the run continues. */
    PER_TRANSFER("per-transfer");

    private final String wire;

    RunMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RunMode fromWire(String value) {
        for (RunMode mode : values()) {
            if (mode.wire.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown run mode: " + value);
    }
}
