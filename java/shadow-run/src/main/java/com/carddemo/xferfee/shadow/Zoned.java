package com.carddemo.xferfee.shadow;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Signed zoned-decimal (overpunch) codec and COBOL numeric display helpers. */
final class Zoned {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private Zoned() {
    }

    static BigDecimal decode(String raw, int scale) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return BigDecimal.ZERO.setScale(scale);
        }
        char last = text.charAt(text.length() - 1);
        int sign = 1;
        int pos = POSITIVE.indexOf(last);
        int neg = NEGATIVE.indexOf(last);
        if (pos >= 0) {
            text = text.substring(0, text.length() - 1) + pos;
        } else if (neg >= 0) {
            text = text.substring(0, text.length() - 1) + neg;
            sign = -1;
        } else if (last >= 'p' && last <= 'y') {
            text = text.substring(0, text.length() - 1) + (last - 'p');
            sign = -1;
        }
        BigDecimal value = new BigDecimal(new BigInteger(text), scale);
        return sign < 0 ? value.negate() : value;
    }

    /** High-order truncation to {@code intDigits} integer digits, as a COBOL receiving field does. */
    static BigDecimal truncate(BigDecimal value, int intDigits, int scale) {
        BigDecimal scaled = value.setScale(scale, java.math.RoundingMode.DOWN);
        BigInteger units = scaled.unscaledValue().abs();
        BigInteger modulus = BigInteger.TEN.pow(intDigits + scale);
        BigDecimal kept = new BigDecimal(units.mod(modulus), scale);
        return scaled.signum() < 0 ? kept.negate() : kept;
    }

    /** DISPLAY of a signed numeric item: sign followed by every digit, no decimal point. */
    static String signedDisplay(BigDecimal value, int intDigits, int scale) {
        BigDecimal kept = truncate(value, intDigits, scale);
        String digits = kept.unscaledValue().abs().toString();
        String padded = "0".repeat(Math.max(0, intDigits + scale - digits.length())) + digits;
        return (kept.signum() < 0 ? "-" : "+") + padded;
    }

    /** PIC Z(8)9.99- edited money. */
    static String editedMoney(BigDecimal value) {
        BigDecimal kept = truncate(value, 9, 2);
        BigDecimal abs = kept.abs();
        String integer = abs.toBigInteger().toString();
        String fraction = abs.remainder(BigDecimal.ONE).movePointRight(2).setScale(0).toPlainString();
        return String.format("%9s.%2s%s", integer, "00".substring(fraction.length()) + fraction,
                kept.signum() < 0 ? "-" : " ");
    }

    /** PIC Z(8)9 edited count. */
    static String editedCount(long value) {
        return String.format("%9d", value % 1_000_000_000L);
    }

    /**
     * How GnuCOBOL 3.1 (ASCII sign, {@code -std=ibm}) reads a trailing-sign DISPLAY field: '0'..'9' positive,
     * 'p'..'y' negative, and anything else - including the EBCDIC-style overpunch '{', 'A'..'I', '}', 'J'..'R' -
     * as a positive 0 in the last digit. Probed with {@code cobc -std=ibm} in the estate image.
     */
    static BigDecimal gnuDecode(String raw, int scale) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return BigDecimal.ZERO.setScale(scale);
        }
        char last = text.charAt(text.length() - 1);
        String head = text.substring(0, text.length() - 1);
        int sign = 1;
        char digit;
        if (last >= '0' && last <= '9') {
            digit = last;
        } else if (last >= 'p' && last <= 'y') {
            digit = (char) ('0' + (last - 'p'));
            sign = -1;
        } else {
            digit = '0';
        }
        BigDecimal value = new BigDecimal(new java.math.BigInteger(head.isEmpty() ? "0" : head + digit), scale);
        if (head.isEmpty()) {
            value = new BigDecimal(new java.math.BigInteger(String.valueOf(digit)), scale);
        }
        return sign < 0 ? value.negate() : value;
    }

    /**
     * The value GnuCOBOL computes with when it reads {@code value} written in the standard overpunch encoding
     * ({@link #decode} / {@code copybook.py}): a non-zero last digit or a negative sign is read as a positive 0.
     */
    static BigDecimal gnuReadBack(BigDecimal value, int scale) {
        java.math.BigInteger units = value.setScale(scale, java.math.RoundingMode.UNNECESSARY).unscaledValue().abs();
        java.math.BigInteger last = units.mod(java.math.BigInteger.TEN);
        return new BigDecimal(units.subtract(last), scale);
    }
}
