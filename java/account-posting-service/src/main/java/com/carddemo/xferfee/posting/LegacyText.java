package com.carddemo.xferfee.posting;

import java.math.BigDecimal;

/** DISPLAY formatting used by XFERFEE SYSOUT lines. */
public final class LegacyText {

    private LegacyText() {
    }

    /** PIC X(n): right-padded / truncated. */
    public static String pad(String value, int length) {
        String text = value == null ? "" : value;
        return text.length() >= length ? text.substring(0, length) : text + " ".repeat(length - text.length());
    }

    /** PIC 9(n): zero-padded, unsigned. */
    public static String digits(long value, int length) {
        String text = Long.toString(Math.abs(value));
        return text.length() >= length ? text.substring(text.length() - length) : "0".repeat(length - text.length()) + text;
    }

    /** DISPLAY of a signed numeric with {@code scale} implied decimals: leading sign, no point. */
    public static String signed(BigDecimal value, int integerDigits, int scale) {
        long units = value.movePointRight(scale).longValueExact();
        return (units < 0 ? "-" : "+") + digits(units, integerDigits + scale);
    }

    /** DISPLAY of SQLCA SQLCODE ({@code PIC S9(9) COMP-5}), e.g. {@code -000000811}. */
    public static String sqlCode(int sqlCode) {
        return signed(BigDecimal.valueOf(sqlCode), 9, 0);
    }
}
