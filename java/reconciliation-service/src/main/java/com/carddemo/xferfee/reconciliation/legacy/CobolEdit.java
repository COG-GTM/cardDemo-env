package com.carddemo.xferfee.reconciliation.legacy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** PICTURE semantics used by CBXFR03C. */
public final class CobolEdit {

    private static final BigDecimal S9_09_LIMIT = new BigDecimal("1000000000");

    private CobolEdit() {
    }

    /** PIC X(n): left-justified, space-padded, truncated on the right. */
    public static String alnum(String value, int length) {
        String text = value == null ? "" : value;
        if (text.length() >= length) {
            return text.substring(0, length);
        }
        return text + " ".repeat(length - text.length());
    }

    /**
     * Store into PIC S9(09)V99: drop digits beyond the scale and high-order integer digits
     * (ADD without ON SIZE ERROR).
     */
    public static BigDecimal fitS9v99(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).remainder(S9_09_LIMIT);
    }

    /** Store into PIC 9(09): unsigned, high-order digits dropped. */
    public static long fit9(long value) {
        return Math.abs(value) % 1_000_000_000L;
    }

    /** PIC Z(8)9.99- (13 characters). */
    public static String editAmount(BigDecimal value) {
        BigDecimal fitted = fitS9v99(value);
        String digits = fitted.abs().setScale(2, RoundingMode.DOWN).toPlainString();
        String sign = fitted.signum() < 0 ? "-" : " ";
        return " ".repeat(12 - digits.length()) + digits + sign;
    }

    /** PIC Z(8)9 (9 characters). */
    public static String editCount(long value) {
        String digits = Long.toString(fit9(value));
        return " ".repeat(9 - digits.length()) + digits;
    }

    /** GnuCOBOL DISPLAY of a PIC S9(09)V99 item: sign followed by 11 digits, no point. */
    public static String displaySigned(BigDecimal value) {
        BigDecimal fitted = fitS9v99(value);
        String digits = fitted.abs().movePointRight(2).toBigInteger().toString();
        return (fitted.signum() < 0 ? "-" : "+") + "0".repeat(11 - digits.length()) + digits;
    }
}
