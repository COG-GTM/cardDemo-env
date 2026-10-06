package com.carddemo.parity.records;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Byte-level helpers for the COBOL USAGE DISPLAY / COMP-3 fields used by the xferfee copybooks. */
public final class Cobol {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private Cobol() {
    }

    public static String text(byte[] record, int offset, int length) {
        return new String(record, offset, length, StandardCharsets.ISO_8859_1);
    }

    public static void putText(byte[] record, int offset, int length, String value) {
        byte[] padded = new byte[length];
        Arrays.fill(padded, (byte) ' ');
        byte[] raw = value.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, length));
        System.arraycopy(padded, 0, record, offset, length);
    }

    public static void copy(byte[] from, int fromOffset, byte[] to, int toOffset, int length) {
        System.arraycopy(from, fromOffset, to, toOffset, length);
    }

    /** Decodes a zoned-decimal field, accepting IBM overpunch and GnuCOBOL ASCII sign encodings. */
    public static BigDecimal zoned(byte[] record, int offset, int length, int scale) {
        String raw = text(record, offset, length);
        char last = raw.charAt(length - 1);
        int sign = 1;
        char digit;
        if (POSITIVE.indexOf(last) >= 0) {
            digit = (char) ('0' + POSITIVE.indexOf(last));
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digit = (char) ('0' + NEGATIVE.indexOf(last));
            sign = -1;
        } else if (last >= 'p' && last <= 'y') {
            digit = (char) ('0' + (last - 'p'));
            sign = -1;
        } else {
            digit = last;
        }
        String digits = (raw.substring(0, length - 1) + digit).replace(' ', '0');
        BigDecimal value = new BigDecimal(new BigInteger(digits), scale);
        return sign < 0 ? value.negate() : value;
    }

    /** Encodes a signed zoned-decimal field the way GnuCOBOL writes arithmetic results. */
    public static void putSignedZoned(byte[] record, int offset, int length, int scale, BigDecimal value) {
        String digits = unscaledDigits(value, length, scale);
        char[] chars = digits.toCharArray();
        if (value.signum() < 0) {
            chars[length - 1] = (char) ('p' + (chars[length - 1] - '0'));
        }
        putText(record, offset, length, new String(chars));
    }

    public static void putUnsigned(byte[] record, int offset, int length, long value) {
        putText(record, offset, length, String.format("%0" + length + "d", value));
    }

    public static long unsigned(byte[] record, int offset, int length) {
        return Long.parseLong(text(record, offset, length).replace(' ', '0'));
    }

    public static void putPacked(byte[] record, int offset, int length, int scale, BigDecimal value) {
        int digitCount = length * 2 - 1;
        String digits = unscaledDigits(value, digitCount, scale);
        int[] nibbles = new int[length * 2];
        for (int i = 0; i < digitCount; i++) {
            nibbles[i] = digits.charAt(i) - '0';
        }
        nibbles[digitCount] = value.signum() < 0 ? 0xD : 0xC;
        for (int i = 0; i < length; i++) {
            record[offset + i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
        }
    }

    public static BigDecimal packed(byte[] record, int offset, int length, int scale) {
        StringBuilder digits = new StringBuilder();
        int signNibble = 0xC;
        for (int i = 0; i < length; i++) {
            int b = record[offset + i] & 0xFF;
            digits.append(b >> 4);
            if (i == length - 1) {
                signNibble = b & 0x0F;
            } else {
                digits.append(b & 0x0F);
            }
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return signNibble == 0xD ? value.negate() : value;
    }

    private static String unscaledDigits(BigDecimal value, int digits, int scale) {
        BigInteger unscaled = value.setScale(scale, java.math.RoundingMode.DOWN).unscaledValue().abs();
        String raw = unscaled.toString();
        if (raw.length() > digits) {
            raw = raw.substring(raw.length() - digits);
        }
        return "0".repeat(digits - raw.length()) + raw;
    }
}
