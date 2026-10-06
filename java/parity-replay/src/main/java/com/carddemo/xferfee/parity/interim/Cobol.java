package com.carddemo.xferfee.parity.interim;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** DISPLAY and edited-picture formatting used by the chain's SYSOUT and report lines. */
final class Cobol {

    /** {@code OCCURS 500} on the xref/account tables of CBXFR01C and XFERFEE; later rows are ignored. */
    static final int TABLE_SIZE = 500;

    private Cobol() {
    }

    static <T> java.util.List<T> table(java.util.List<T> rows) {
        return rows.size() > TABLE_SIZE ? rows.subList(0, TABLE_SIZE) : rows;
    }

    /** DISPLAY of an unsigned {@code PIC 9(n)}. */
    static String unsigned(long value, int digits) {
        return String.format("%0" + digits + "d", Math.abs(value));
    }

    /** DISPLAY of a {@code PIC S9(9)V99} item: leading sign, 11 digits, no decimal point. */
    static String signed(BigDecimal value) {
        long cents = value.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact();
        return (cents < 0 ? "-" : "+") + unsigned(cents, 11);
    }

    /** {@code PIC Z(8)9.99-}. */
    static String amountEdit(BigDecimal value) {
        long cents = Math.abs(value.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact()) % 100_000_000_000L;
        return String.format("%9d.%02d", cents / 100, cents % 100) + (value.signum() < 0 ? "-" : " ");
    }

    /** {@code PIC Z(8)9}. */
    static String countEdit(long value) {
        return String.format("%9d", value % 1_000_000_000L);
    }

    /** {@code PIC X(n)}: pad or truncate. */
    static String pic(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }
}
