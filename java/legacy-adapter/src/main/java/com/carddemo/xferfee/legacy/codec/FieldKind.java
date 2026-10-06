package com.carddemo.xferfee.legacy.codec;

public enum FieldKind {
    /** {@code PIC X(n)}. */
    ALPHANUMERIC,
    /** {@code PIC 9 / S9 [V9]} USAGE DISPLAY, sign overpunched on the last byte. */
    ZONED,
    /** {@code PIC S9 [V9] COMP-3}. */
    PACKED
}
