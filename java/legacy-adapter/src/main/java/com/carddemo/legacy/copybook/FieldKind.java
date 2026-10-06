package com.carddemo.legacy.copybook;

/** Storage class of an elementary copybook item. */
public enum FieldKind {
  /** {@code PIC X(n)} - single-byte character data. */
  ALPHANUMERIC,
  /** {@code PIC [S]9(n)[V9(m)]} USAGE DISPLAY - one digit per byte, sign overpunched on the last byte. */
  ZONED,
  /** {@code PIC [S]9(n)[V9(m)] COMP-3} - two digits per byte, sign in the trailing nibble. */
  PACKED
}
