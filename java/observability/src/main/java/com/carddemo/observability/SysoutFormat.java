package com.carddemo.observability;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Renders counters exactly as GnuCOBOL DISPLAYs them in the legacy SYSOUT. */
public final class SysoutFormat {

    private SysoutFormat() {
    }

    public static String value(LegacyCounter counter, BigDecimal value) {
        if (counter.kind() == LegacyCounter.Kind.COUNT) {
            return String.format("%09d", value.longValueExact());
        }
        long cents = value.setScale(2, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
        return (cents < 0 ? "-" : "+") + String.format("%011d", Math.abs(cents));
    }

    public static String line(LegacyCounter counter, BigDecimal value) {
        return counter.program() + ": " + counter.label() + " " + value(counter, value);
    }
}
