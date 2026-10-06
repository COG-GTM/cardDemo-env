package com.carddemo.legacy.codec;

import com.carddemo.legacy.copybook.CopybookField;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

final class Numeric {
  private Numeric() {}

  /**
   * Scaled integer units for a numeric field. Excess fraction digits are truncated (COBOL MOVE
   * semantics); integer overflow is rejected rather than silently dropping high-order digits.
   */
  static BigInteger units(CopybookField field, BigDecimal value) {
    BigInteger units = value.setScale(field.scale(), RoundingMode.DOWN).unscaledValue();
    if (units.abs().toString().length() > field.digits()) {
      throw new RecordFormatException(
          field.name() + ": value " + value.toPlainString() + " exceeds " + field.digits() + " digits");
    }
    if (units.signum() < 0 && !field.signed()) {
      throw new RecordFormatException(
          field.name() + ": negative value " + value.toPlainString() + " for unsigned field");
    }
    return units;
  }
}
