package com.carddemo.parity;

import java.math.BigDecimal;

/** COBOL DISPLAY of a signed numeric item: explicit sign then zero-padded digits, no decimal point. */
final class LegacyDisplay {

    private LegacyDisplay() {
    }

    static String signed(BigDecimal value, int digits, int scale) {
        BigDecimal units = value.movePointRight(scale).abs();
        return (value.signum() < 0 ? "-" : "+")
                + String.format("%0" + digits + "d", units.toBigIntegerExact());
    }
}
