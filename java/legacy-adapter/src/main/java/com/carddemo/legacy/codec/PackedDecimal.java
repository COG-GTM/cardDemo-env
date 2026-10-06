package com.carddemo.legacy.codec;

import com.carddemo.legacy.copybook.CopybookField;
import java.math.BigDecimal;
import java.math.BigInteger;

/** COMP-3 (packed decimal) numeric fields. */
final class PackedDecimal {
  private PackedDecimal() {}

  static BigDecimal decode(CopybookField field, byte[] data, int offset) {
    int len = field.length();
    StringBuilder digits = new StringBuilder(len * 2);
    for (int i = 0; i < len; i++) {
      int b = data[offset + i] & 0xFF;
      digits.append(nibble(field, b >> 4, i));
      if (i < len - 1) {
        digits.append(nibble(field, b & 0x0F, i));
      }
    }
    int sign = data[offset + len - 1] & 0x0F;
    if (sign < 0x0A) {
      throw new RecordFormatException(
          String.format("%s: invalid packed sign nibble 0x%X", field.name(), sign));
    }
    boolean negative = field.signed() && (sign == 0x0D || sign == 0x0B);
    BigInteger units = new BigInteger(digits.toString());
    return new BigDecimal(negative ? units.negate() : units, field.scale());
  }

  /** Writes C/D sign nibbles for signed fields and F for unsigned fields, as IBM COBOL does. */
  static void encode(CopybookField field, BigDecimal value, byte[] out, int offset) {
    BigInteger units = Numeric.units(field, value);
    int len = field.length();
    int nibbles = len * 2;
    String digits = units.abs().toString();
    int[] n = new int[nibbles];
    for (int i = 0; i < nibbles - 1; i++) {
      int src = digits.length() - (nibbles - 1) + i;
      n[i] = src < 0 ? 0 : digits.charAt(src) - '0';
    }
    n[nibbles - 1] = !field.signed() ? 0x0F : units.signum() < 0 ? 0x0D : 0x0C;
    for (int i = 0; i < len; i++) {
      out[offset + i] = (byte) ((n[i * 2] << 4) | n[i * 2 + 1]);
    }
  }

  private static int nibble(CopybookField field, int value, int position) {
    if (value > 9) {
      throw new RecordFormatException(
          String.format(
              "%s: invalid packed digit 0x%X at offset %d", field.name(), value, field.offset() + position));
    }
    return value;
  }
}
