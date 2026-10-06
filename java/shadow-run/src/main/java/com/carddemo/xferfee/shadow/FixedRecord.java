package com.carddemo.xferfee.shadow;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/** Builds one fixed-length record field by field, mirroring the copybook layout. Filler is LOW-VALUES. */
final class FixedRecord {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int length;

    FixedRecord(int length) {
        this.length = length;
    }

    FixedRecord text(String value, int size) {
        String padded = value.length() >= size ? value.substring(0, size) : value + " ".repeat(size - value.length());
        out.writeBytes(padded.getBytes(StandardCharsets.ISO_8859_1));
        return this;
    }

    FixedRecord unsigned(long value, int digits) {
        String text = Long.toString(Math.abs(value));
        if (text.length() > digits) {
            text = text.substring(text.length() - digits);
        }
        return text("0".repeat(digits - text.length()) + text, digits);
    }

    FixedRecord zoned(BigDecimal value, int intDigits, int scale) {
        BigDecimal kept = Zoned.truncate(value, intDigits, scale);
        String digits = units(kept, intDigits + scale);
        int last = digits.charAt(digits.length() - 1) - '0';
        char punch = (kept.signum() < 0 ? NEGATIVE : POSITIVE).charAt(last);
        return text(digits.substring(0, digits.length() - 1) + punch, intDigits + scale);
    }

    /**
     * A signed DISPLAY field written by GnuCOBOL arithmetic on ASCII: positive keeps a plain digit in the last
     * position, negative sets it to {@code 0x70 | digit} ('p'..'y').
     */
    FixedRecord computedZoned(BigDecimal value, int intDigits, int scale) {
        BigDecimal kept = Zoned.truncate(value, intDigits, scale);
        String digits = units(kept, intDigits + scale);
        if (kept.signum() < 0) {
            char last = (char) (0x70 | (digits.charAt(digits.length() - 1) - '0'));
            digits = digits.substring(0, digits.length() - 1) + last;
        }
        return text(digits, intDigits + scale);
    }

    FixedRecord packed(BigDecimal value, int intDigits, int scale) {
        BigDecimal kept = Zoned.truncate(value, intDigits, scale);
        int bytes = (intDigits + scale) / 2 + 1;
        String digits = units(kept, bytes * 2 - 1);
        int[] nibbles = new int[bytes * 2];
        for (int i = 0; i < digits.length(); i++) {
            nibbles[i] = digits.charAt(i) - '0';
        }
        nibbles[nibbles.length - 1] = kept.signum() < 0 ? 0xD : 0xC;
        for (int i = 0; i < nibbles.length; i += 2) {
            out.write((nibbles[i] << 4) | nibbles[i + 1]);
        }
        return this;
    }

    byte[] bytes() {
        byte[] body = out.toByteArray();
        if (body.length > length) {
            throw new IllegalStateException("record overflow: " + body.length + " > " + length);
        }
        byte[] record = new byte[length];
        System.arraycopy(body, 0, record, 0, body.length);
        return record;
    }

    private static String units(BigDecimal value, int width) {
        String digits = value.abs().setScale(value.scale(), RoundingMode.UNNECESSARY).unscaledValue().toString();
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }
}
