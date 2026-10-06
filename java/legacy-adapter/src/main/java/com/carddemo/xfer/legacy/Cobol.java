package com.carddemo.xfer.legacy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Byte-level codecs for the DISPLAY, overpunched-sign and COMP-3 fields used by the chain. */
public final class Cobol {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private Cobol() {
    }

    public static String text(byte[] record, int offset, int length) {
        return new String(record, offset, length, StandardCharsets.US_ASCII);
    }

    public static BigDecimal zoned(byte[] record, int offset, int length, int scale) {
        char[] chars = text(record, offset, length).toCharArray();
        int sign = 1;
        char last = chars[length - 1];
        int positive = POSITIVE.indexOf(last);
        int negative = NEGATIVE.indexOf(last);
        if (positive >= 0) {
            chars[length - 1] = (char) ('0' + positive);
        } else if (negative >= 0) {
            chars[length - 1] = (char) ('0' + negative);
            sign = -1;
        } else if (last >= 'p' && last <= 'y') {
            chars[length - 1] = (char) ('0' + (last - 'p'));
            sign = -1;
        }
        StringBuilder digits = new StringBuilder(length);
        for (char c : chars) {
            digits.append(Character.isDigit(c) ? c : '0');
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return sign < 0 ? value.negate() : value;
    }

    public static long unsigned(byte[] record, int offset, int length) {
        return zoned(record, offset, length, 0).longValueExact();
    }

    public static void putText(byte[] record, int offset, int length, String value) {
        String padded = String.format("%-" + length + "s", value == null ? "" : value);
        byte[] bytes = padded.substring(0, length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, record, offset, length);
    }

    public static void putUnsigned(byte[] record, int offset, int length, long value) {
        String digits = String.format("%0" + length + "d", Math.abs(value));
        putText(record, offset, length, digits.substring(digits.length() - length));
    }

    public static void putZoned(byte[] record, int offset, int length, int scale, BigDecimal value) {
        BigInteger units = value.setScale(scale).unscaledValue();
        String digits = String.format("%0" + length + "d", units.abs());
        digits = digits.substring(digits.length() - length);
        int last = digits.charAt(length - 1) - '0';
        char overpunch = (units.signum() < 0 ? NEGATIVE : POSITIVE).charAt(last);
        putText(record, offset, length, digits.substring(0, length - 1) + overpunch);
    }

    /**
     * Zoned field as GnuCOBOL writes it after ADD/SUBTRACT/COMPUTE: unsigned digit when
     * positive, ASCII {@code p}-{@code y} last byte when negative.
     */
    public static void putZonedComputed(byte[] record, int offset, int length, int scale, BigDecimal value) {
        BigInteger units = value.setScale(scale).unscaledValue();
        String digits = String.format("%0" + length + "d", units.abs());
        digits = digits.substring(digits.length() - length);
        char last = digits.charAt(length - 1);
        if (units.signum() < 0) {
            last = (char) ('p' + (last - '0'));
        }
        putText(record, offset, length, digits.substring(0, length - 1) + last);
    }

    public static void putPacked(byte[] record, int offset, int length, int scale, BigDecimal value) {
        BigInteger units = value.setScale(scale).unscaledValue();
        String digits = String.format("%0" + (length * 2 - 1) + "d", units.abs());
        digits = digits.substring(digits.length() - (length * 2 - 1));
        int[] nibbles = new int[length * 2];
        for (int i = 0; i < digits.length(); i++) {
            nibbles[i] = digits.charAt(i) - '0';
        }
        nibbles[length * 2 - 1] = units.signum() < 0 ? 0xD : 0xC;
        for (int i = 0; i < length; i++) {
            record[offset + i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
        }
    }

    public static BigDecimal packed(byte[] record, int offset, int length, int scale) {
        StringBuilder digits = new StringBuilder();
        int sign = 1;
        for (int i = 0; i < length; i++) {
            int high = (record[offset + i] >> 4) & 0x0F;
            int low = record[offset + i] & 0x0F;
            digits.append(high);
            if (i == length - 1) {
                sign = low == 0xD ? -1 : 1;
            } else {
                digits.append(low);
            }
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return sign < 0 ? value.negate() : value;
    }

    /** DISPLAY of a signed numeric item: {@code +00000000650} for S9(09)V99. */
    public static String displaySigned(BigDecimal value, int digits, int scale) {
        BigInteger units = value.setScale(scale).unscaledValue();
        return (units.signum() < 0 ? "-" : "+") + String.format("%0" + digits + "d", units.abs());
    }

    /** DISPLAY of an unsigned PIC 9(n) counter. */
    public static String displayUnsigned(long value, int digits) {
        return String.format("%0" + digits + "d", value);
    }

    /** Edited picture {@code Z(8)9.99-}. */
    public static String editAmount(BigDecimal value) {
        BigDecimal scaled = value.setScale(2);
        String body = String.format("%12s", scaled.abs().toPlainString());
        return body + (scaled.signum() < 0 ? "-" : " ");
    }

    /** Edited picture {@code Z(8)9}. */
    public static String editCount(long value) {
        return String.format("%9d", value);
    }
}
