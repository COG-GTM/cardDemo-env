package com.carddemo.xferfee.legacy.codec;

/**
 * One elementary item of a copybook layout.
 *
 * @param digits total digit count for numeric items (0 for alphanumeric)
 * @param scale  implied decimal places ({@code V99} = 2)
 */
public record FieldSpec(
        String name, int offset, int length, FieldKind kind, int digits, int scale, boolean signed) {

    public boolean isFiller() {
        return name.startsWith("FILLER");
    }

    public boolean isNumeric() {
        return kind != FieldKind.ALPHANUMERIC;
    }

    public int end() {
        return offset + length;
    }
}
