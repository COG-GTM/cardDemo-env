package com.carddemo.xferfee.legacy.codec;

/**
 * How a numeric item carries its sign. Decoding accepts every style; encoding uses the style the
 * caller asks for so that a decoded record re-encodes to the exact original bytes.
 */
public enum SignStyle {
    /** Unsigned zoned digits ({@code PIC 9}). */
    UNSIGNED,
    /**
     * Trailing overpunch, IBM style: {@code '{','A'..'I'} for +0..+9 and {@code '}','J'..'R'} for
     * -0..-9. Used by the generated fixture inputs and preserved by COBOL MOVEs between identical
     * pictures.
     */
    OVERPUNCH,
    /**
     * GnuCOBOL's native ASCII sign: positive is a plain digit, negative is {@code 0x70 + digit}
     * ({@code 'p'..'y'}). Every arithmetic result (ADD/SUBTRACT/COMPUTE) and every MOVE between
     * different pictures is stored this way by the legacy chain.
     */
    NATIVE,
    /** COMP-3 with {@code C} (positive) / {@code D} (negative) sign nibble. */
    PACKED_C,
    /** COMP-3 with {@code F} (unsigned) sign nibble. */
    PACKED_F
}
