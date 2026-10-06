package com.carddemo.xferfee.legacy.codec;

import java.math.BigDecimal;

final class Numbers {

    private Numbers() {
    }

    /** Absolute unscaled digits, left-padded to the field's digit count; never truncates. */
    static String digits(BigDecimal value, FieldSpec field) {
        if (value.signum() < 0 && !field.signed()) {
            throw new CopybookDataException(field.name() + ": negative value " + value + " for unsigned field");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(field.scale());
        } catch (ArithmeticException e) {
            throw new CopybookDataException(field.name() + ": " + value + " has more than " + field.scale() + " decimal places", e);
        }
        String digits = scaled.unscaledValue().abs().toString();
        if (digits.length() > field.digits()) {
            throw new CopybookDataException(field.name() + ": " + value + " overflows " + field.digits() + " digits");
        }
        return "0".repeat(field.digits() - digits.length()) + digits;
    }

    static BigDecimal toDecimal(Object value, FieldSpec field) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof java.math.BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof CharSequence text) {
            try {
                return new BigDecimal(text.toString().trim());
            } catch (NumberFormatException e) {
                throw new CopybookDataException(field.name() + ": not a number: '" + text + "'", e);
            }
        }
        throw new CopybookDataException(field.name() + ": unsupported numeric value type " + value.getClass().getName());
    }
}
