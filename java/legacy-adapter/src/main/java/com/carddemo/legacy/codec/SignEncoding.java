package com.carddemo.legacy.codec;

/** How the sign of a signed zoned-decimal (DISPLAY) field is carried in its last byte, in ASCII. */
public enum SignEncoding {
  /**
   * IBM overpunch translated to ASCII: {@code {,A-I} = +0..+9}, {@code },J-R} = -0..-9}. Used by the
   * mainframe-originated {@code .PS} input extracts.
   */
  OVERPUNCH("{ABCDEFGHI", "}JKLMNOPQR"),
  /**
   * GnuCOBOL native ASCII sign: positive is the plain digit, negative is {@code p-y = -0..-9}. Used by
   * datasets written by the GnuCOBOL estate.
   */
  GNUCOBOL("0123456789", "pqrstuvwxy");

  private final String positive;
  private final String negative;

  SignEncoding(String positive, String negative) {
    this.positive = positive;
    this.negative = negative;
  }

  char encode(int digit, boolean negativeValue) {
    return (negativeValue ? negative : positive).charAt(digit);
  }

  /** Classifies a trailing zoned byte; returns {@code null} if this convention cannot produce it. */
  Signed decode(char c) {
    int p = positive.indexOf(c);
    if (p >= 0) {
      return new Signed(p, false);
    }
    int n = negative.indexOf(c);
    return n >= 0 ? new Signed(n, true) : null;
  }

  /** True if {@code c} can only have been produced by this convention. */
  boolean isDistinctive(char c) {
    return decode(c) != null && !(c >= '0' && c <= '9');
  }

  record Signed(int digit, boolean negative) {}
}
