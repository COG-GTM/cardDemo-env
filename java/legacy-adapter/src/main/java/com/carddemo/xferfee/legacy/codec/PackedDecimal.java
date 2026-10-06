package com.carddemo.xferfee.legacy.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/** COMP-3 packed decimal: two digits per byte, sign in the low nibble of the last byte. */
public final class PackedDecimal {

    private PackedDecimal() {
    }

    /** {@code negativeZero}: a zero carrying a negative sign nibble, which BigDecimal cannot hold. */
    public record Decoded(BigDecimal value, SignStyle style, boolean negativeZero) {
    }

    public static Decoded decode(byte[] data, int offset, FieldSpec field) {
        StringBuilder digits = new StringBuilder(field.length() * 2);
        for (int i = 0; i < field.length(); i++) {
            int b = data[offset + i] & 0xFF;
            digits.append(nibble(field, b >> 4, data, offset));
            if (i < field.length() - 1) {
                digits.append(nibble(field, b & 0x0F, data, offset));
            }
        }
        int sign = data[offset + field.length() - 1] & 0x0F;
        boolean negative;
        SignStyle style;
        switch (sign) {
            case 0x0C -> {
                negative = false;
                style = SignStyle.PACKED_C;
            }
            case 0x0A -> {
                negative = false;
                style = SignStyle.PACKED_A;
            }
            case 0x0E -> {
                negative = false;
                style = SignStyle.PACKED_E;
            }
            case 0x0F -> {
                negative = false;
                style = SignStyle.PACKED_F;
            }
            case 0x0D -> {
                negative = true;
                style = SignStyle.PACKED_C;
            }
            case 0x0B -> {
                negative = true;
                style = SignStyle.PACKED_B;
            }
            default -> throw new CopybookDataException(field.name() + ": invalid packed sign nibble " + Integer.toHexString(sign));
        }
        if (negative && !field.signed()) {
            throw new CopybookDataException(field.name() + ": negative value in unsigned packed field");
        }
        BigInteger unscaled = new BigInteger(digits.toString());
        if (unscaled.toString().length() > field.digits()) {
            throw new CopybookDataException(field.name() + ": packed value exceeds " + field.digits() + " digits");
        }
        return new Decoded(new BigDecimal(negative ? unscaled.negate() : unscaled, field.scale()), style,
                negative && unscaled.signum() == 0);
    }

    private static char nibble(FieldSpec field, int value, byte[] data, int offset) {
        if (value > 9) {
            throw new CopybookDataException(field.name() + ": invalid packed digit nibble at offset " + offset);
        }
        return (char) ('0' + value);
    }

    public static void encode(BigDecimal value, FieldSpec field, SignStyle style, byte[] target, int offset) {
        encode(value, field, style, false, target, offset);
    }

    /** {@code negativeZero} re-emits a negative sign nibble when {@code value} is zero. */
    public static void encode(BigDecimal value, FieldSpec field, SignStyle style, boolean negativeZero,
            byte[] target, int offset) {
        String digits = Numbers.digits(value, field);
        int nibbles = field.length() * 2 - 1;
        String padded = "0".repeat(nibbles - digits.length()) + digits;
        boolean negative = value.signum() < 0 || (negativeZero && value.signum() == 0 && field.signed());
        int sign;
        if (negative) {
            sign = style == SignStyle.PACKED_B ? 0x0B : 0x0D;
        } else if (style == SignStyle.PACKED_F || (style == null && !field.signed())) {
            sign = 0x0F;
        } else if (style == SignStyle.PACKED_A) {
            sign = 0x0A;
        } else if (style == SignStyle.PACKED_E) {
            sign = 0x0E;
        } else {
            sign = 0x0C;
        }
        for (int i = 0; i < field.length(); i++) {
            int high = padded.charAt(i * 2) - '0';
            int low = i == field.length() - 1 ? sign : padded.charAt(i * 2 + 1) - '0';
            target[offset + i] = (byte) ((high << 4) | low);
        }
    }
}
