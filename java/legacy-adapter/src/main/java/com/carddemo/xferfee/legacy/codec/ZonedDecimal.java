package com.carddemo.xferfee.legacy.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Zoned decimal (USAGE DISPLAY numeric) with trailing overpunch sign. Operates on characters. */
public final class ZonedDecimal {

    static final String POSITIVE = "{ABCDEFGHI";
    static final String NEGATIVE = "}JKLMNOPQR";

    private ZonedDecimal() {
    }

    public record Decoded(BigDecimal value, SignStyle style) {
    }

    public static Decoded decode(String text, FieldSpec field) {
        if (text.length() != field.digits()) {
            throw new CopybookDataException(field.name() + ": expected " + field.digits() + " digits, got " + text.length());
        }
        char last = text.charAt(text.length() - 1);
        int digit;
        boolean negative = false;
        SignStyle style;
        if (last >= '0' && last <= '9') {
            digit = last - '0';
            style = field.signed() ? SignStyle.NATIVE : SignStyle.UNSIGNED;
        } else if (POSITIVE.indexOf(last) >= 0) {
            digit = POSITIVE.indexOf(last);
            style = SignStyle.OVERPUNCH;
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digit = NEGATIVE.indexOf(last);
            negative = true;
            style = SignStyle.OVERPUNCH;
        } else if (last >= 'p' && last <= 'y') {
            digit = last - 'p';
            negative = true;
            style = SignStyle.NATIVE;
        } else {
            throw new CopybookDataException(field.name() + ": invalid sign/digit '" + last + "' in '" + text + "'");
        }
        if (negative && !field.signed()) {
            throw new CopybookDataException(field.name() + ": negative value in unsigned field: '" + text + "'");
        }
        String body = text.substring(0, text.length() - 1);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c < '0' || c > '9') {
                throw new CopybookDataException(field.name() + ": non-numeric '" + text + "'");
            }
        }
        BigInteger unscaled = new BigInteger(body + digit);
        BigDecimal value = new BigDecimal(negative ? unscaled.negate() : unscaled, field.scale());
        return new Decoded(value, style);
    }

    public static String encode(BigDecimal value, FieldSpec field, SignStyle style) {
        String digits = Numbers.digits(value, field);
        boolean negative = value.signum() < 0;
        int last = digits.charAt(digits.length() - 1) - '0';
        SignStyle effective = style;
        if (effective == SignStyle.UNSIGNED && field.signed()) {
            effective = SignStyle.NATIVE;
        }
        char sign = switch (effective) {
            case OVERPUNCH -> (negative ? NEGATIVE : POSITIVE).charAt(last);
            case NATIVE -> negative ? (char) ('p' + last) : (char) ('0' + last);
            case UNSIGNED -> (char) ('0' + last);
            default -> throw new IllegalArgumentException(field.name() + ": " + style + " is not a zoned sign style");
        };
        return digits.substring(0, digits.length() - 1) + sign;
    }
}
