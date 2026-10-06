package com.carddemo.xferfee.observability;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Reproduces GnuCOBOL DISPLAY output for the picture clauses used by the chain's counters. */
public final class SysoutFormat {

    private static final BigInteger COUNT_MODULUS = BigInteger.TEN.pow(9);
    private static final BigInteger MONEY_MODULUS = BigInteger.TEN.pow(11);

    private SysoutFormat() {
    }

    /** {@code PIC 9(09)}: nine digits, zero padded, high-order digits truncated. */
    public static String count(long value) {
        BigInteger digits = BigInteger.valueOf(value).abs().mod(COUNT_MODULUS);
        return String.format("%09d", digits);
    }

    /** {@code PIC S9(09)V99 COMP-3}: sign then eleven digits with an implied decimal point. */
    public static String signedMoney(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, java.math.RoundingMode.DOWN);
        String sign = scaled.signum() < 0 ? "-" : "+";
        BigInteger digits = scaled.unscaledValue().abs().mod(MONEY_MODULUS);
        return sign + String.format("%011d", digits);
    }

    /** {@code PIC 9(11)}. */
    public static String accountId(long value) {
        return String.format("%011d", value);
    }

    /** {@code PIC X(n)}: left aligned, space padded, truncated. */
    public static String text(String value, int length) {
        String source = value == null ? "" : value;
        if (source.length() >= length) {
            return source.substring(0, length);
        }
        return source + " ".repeat(length - source.length());
    }
}
