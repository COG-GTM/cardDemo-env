package com.carddemo.legacy.codec;

import com.carddemo.legacy.copybook.CopybookField;
import java.math.BigDecimal;
import java.math.BigInteger;

/** USAGE DISPLAY numeric fields in ASCII. */
final class ZonedDecimal {
  private ZonedDecimal() {}

  /** Decoded value plus the sign convention its last byte proves, if any. */
  record Decoded(BigDecimal value, SignEncoding evidence) {}

  static Decoded decode(CopybookField field, byte[] data, int offset) {
    int len = field.length();
    StringBuilder digits = new StringBuilder(len);
    for (int i = 0; i < len - 1; i++) {
      char c = (char) (data[offset + i] & 0xFF);
      digits.append(digit(field, c, i));
    }
    char last = (char) (data[offset + len - 1] & 0xFF);
    boolean negative = false;
    SignEncoding evidence = null;
    if (last >= '0' && last <= '9') {
      digits.append(last);
    } else {
      SignEncoding.Signed signed = null;
      for (SignEncoding encoding : SignEncoding.values()) {
        signed = encoding.decode(last);
        if (signed != null) {
          evidence = encoding;
          break;
        }
      }
      if (signed == null) {
        throw invalid(field, last, len - 1);
      }
      digits.append(signed.digit());
      negative = signed.negative() && field.signed();
    }
    BigInteger units = new BigInteger(digits.toString());
    return new Decoded(new BigDecimal(negative ? units.negate() : units, field.scale()), evidence);
  }

  static void encode(CopybookField field, BigDecimal value, SignEncoding sign, byte[] out, int offset) {
    BigInteger units = Numeric.units(field, value);
    boolean negative = units.signum() < 0 && field.signed();
    String digits = units.abs().toString();
    int len = field.length();
    for (int i = 0; i < len; i++) {
      int src = digits.length() - len + i;
      out[offset + i] = (byte) (src < 0 ? '0' : digits.charAt(src));
    }
    if (field.signed()) {
      int lastDigit = out[offset + len - 1] - '0';
      out[offset + len - 1] = (byte) sign.encode(lastDigit, negative);
    }
  }

  private static char digit(CopybookField field, char c, int position) {
    if (c >= '0' && c <= '9') {
      return c;
    }
    if (c == ' ') {
      return '0';
    }
    throw invalid(field, c, position);
  }

  private static RecordFormatException invalid(CopybookField field, char c, int position) {
    return new RecordFormatException(
        String.format(
            "%s: invalid zoned byte 0x%02X at offset %d", field.name(), (int) c, field.offset() + position));
  }
}
