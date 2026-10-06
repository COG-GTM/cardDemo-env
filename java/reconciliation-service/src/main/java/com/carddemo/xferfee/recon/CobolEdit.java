package com.carddemo.xferfee.recon;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** GnuCOBOL MOVE / DISPLAY semantics for the pictures used by CBXFR03C. */
final class CobolEdit {

    private static final BigInteger NINE_DIGITS = BigInteger.TEN.pow(9);

    private CobolEdit() {
    }

    /** Fit a value into S9(n)V9(scale): truncate fraction, drop high-order digits. */
    static BigDecimal fit(BigDecimal value, int integerDigits, int scale) {
        BigDecimal truncated = value.setScale(scale, RoundingMode.DOWN);
        BigDecimal limit = BigDecimal.TEN.pow(integerDigits);
        return truncated.remainder(limit).setScale(scale, RoundingMode.DOWN);
    }

    /** PIC Z(8)9.99- (13 characters). */
    static String amount(BigDecimal value) {
        BigDecimal fitted = fit(value, 9, 2);
        BigDecimal magnitude = fitted.abs();
        BigInteger units = magnitude.unscaledValue();
        BigInteger[] parts = units.divideAndRemainder(BigInteger.valueOf(100));
        String integer = leftPad(parts[0].toString(), 9);
        String fraction = String.format("%02d", parts[1].intValue());
        return integer + "." + fraction + (fitted.signum() < 0 ? "-" : " ");
    }

    /** PIC Z(8)9 (9 characters). */
    static String count(long value) {
        BigInteger fitted = BigInteger.valueOf(value).abs().mod(NINE_DIGITS);
        return leftPad(fitted.toString(), 9);
    }

    /** DISPLAY of PIC S9(09)V99: sign followed by 11 digits, no point. */
    static String displaySigned(BigDecimal value) {
        BigDecimal fitted = fit(value, 9, 2);
        String digits = fitted.abs().unscaledValue().toString();
        return (fitted.signum() < 0 ? "-" : "+") + "0".repeat(11 - digits.length()) + digits;
    }

    /** PIC X(n) content: right-pad with spaces or truncate. */
    static String text(String value, int length) {
        String source = value == null ? "" : value;
        if (source.length() >= length) {
            return source.substring(0, length);
        }
        return source + " ".repeat(length - source.length());
    }

    private static String leftPad(String value, int length) {
        return " ".repeat(Math.max(0, length - value.length())) + value;
    }
}
