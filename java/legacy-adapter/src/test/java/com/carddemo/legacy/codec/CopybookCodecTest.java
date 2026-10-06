package com.carddemo.legacy.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.legacy.copybook.CopybookLayout;
import com.carddemo.legacy.copybook.CopybookParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CopybookCodecTest {
  private static final CopybookLayout LAYOUT =
      CopybookParser.parse(
          "T",
          List.of(
              "       01  T-REC.",
              "           05  T-ID      PIC X(04).",
              "           05  T-AMT     PIC S9(3)V99.",
              "           05  T-CNT     PIC 9(03).",
              "           05  T-PCK     PIC S9(3)V99 COMP-3.",
              "           05  T-UPK     PIC 9(04) COMP-3.",
              "           05  FILLER    PIC X(03)."));

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] record(String zoned, byte[] packed, String filler) {
    byte[] head = ascii(zoned);
    byte[] tail = ascii(filler);
    byte[] out = new byte[head.length + packed.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(packed, 0, out, head.length, packed.length);
    System.arraycopy(tail, 0, out, head.length + packed.length, tail.length);
    return out;
  }

  private static final byte[] PACKED = {0x12, 0x34, 0x5D, 0x00, 0x04, 0x2F};

  @ParameterizedTest
  @CsvSource({
    "1234{, 123.40, OVERPUNCH",
    "1234E, 123.45, OVERPUNCH",
    "1234}, -123.40, OVERPUNCH",
    "1234N, -123.45, OVERPUNCH",
    "12345, 123.45, GNUCOBOL",
    "1234p, -123.40, GNUCOBOL",
    "1234u, -123.45, GNUCOBOL"
  })
  void decodesAndReencodesBothZonedSignConventions(String amt, String value, SignEncoding sign) {
    byte[] bytes = record("AB  " + amt + "007", PACKED, "xyz");
    CopybookCodec codec = new CopybookCodec(LAYOUT, sign);
    CopybookRecord r = codec.decode(bytes);
    assertEquals(new BigDecimal(value), r.decimal("T-AMT"));
    assertEquals("AB", r.string("T-ID"));
    assertEquals(new BigDecimal("7"), r.decimal("T-CNT"));
    assertEquals(new BigDecimal("-123.45"), r.decimal("T-PCK"));
    assertEquals(new BigDecimal("42"), r.decimal("T-UPK"));
    assertArrayEquals(bytes, codec.encode(r));
    assertEquals(Set.of(sign), codec.signEncodingsUsed(bytes));
  }

  @ParameterizedTest
  @CsvSource({"-123.45, OVERPUNCH, 1234N", "-123.45, GNUCOBOL, 1234u", "0.10, OVERPUNCH, 0001{", "0.10, GNUCOBOL, 00010"})
  void changedFieldsAreWrittenWithTheCodecConvention(String value, SignEncoding sign, String zoned) {
    byte[] bytes = record("AB  1234{007", PACKED, "xyz");
    CopybookCodec codec = new CopybookCodec(LAYOUT, sign);
    byte[] out = codec.encode(codec.decode(bytes).toBuilder().set("T-AMT", value).build());
    assertEquals(zoned, new String(out, 4, 5, StandardCharsets.ISO_8859_1));
    assertEquals("AB  ", new String(out, 0, 4, StandardCharsets.ISO_8859_1));
    assertEquals("xyz", new String(out, 18, 3, StandardCharsets.ISO_8859_1));
  }

  @Test
  void preservesFillerIncludingLowValuesButBlanksItForNewRecords() {
    byte[] bytes = record("ABCD12345999", PACKED, "\0\0\0");
    CopybookCodec codec = new CopybookCodec(LAYOUT, SignEncoding.GNUCOBOL);
    CopybookRecord decoded = codec.decode(bytes);
    assertArrayEquals(bytes, codec.encode(decoded));
    byte[] changed = codec.encode(decoded.toBuilder().set("T-ID", "Z").build());
    assertEquals("Z   ", new String(changed, 0, 4, StandardCharsets.ISO_8859_1));
    assertEquals(0, changed[changed.length - 1]);

    byte[] fresh = codec.encode(CopybookRecord.builder(LAYOUT).set("T-AMT", "-1.5").set("T-UPK", 7).build());
    assertArrayEquals(record("    0015p000", new byte[] {0x00, 0x00, 0x0C, 0x00, 0x00, 0x7F}, "   "), fresh);
  }

  @Test
  void encodesSignedPackedWithCAndDNibbles() {
    CopybookCodec codec = new CopybookCodec(LAYOUT, SignEncoding.OVERPUNCH);
    byte[] pos = codec.encode(CopybookRecord.builder(LAYOUT).set("T-PCK", new BigDecimal("1.23")).build());
    assertArrayEquals(new byte[] {0x00, 0x12, 0x3C}, java.util.Arrays.copyOfRange(pos, 12, 15));
    byte[] neg = codec.encode(CopybookRecord.builder(LAYOUT).set("T-PCK", new BigDecimal("-999.99")).build());
    assertArrayEquals(new byte[] {(byte) 0x99, (byte) 0x99, (byte) 0x9D}, java.util.Arrays.copyOfRange(neg, 12, 15));
  }

  @Test
  void truncatesExcessDecimalsLikeCobolMove() {
    CopybookRecord r = CopybookRecord.builder(LAYOUT).set("T-AMT", new BigDecimal("1.239")).build();
    assertEquals(new BigDecimal("1.23"), r.decimal("T-AMT"));
  }

  @Test
  void rejectsOverflowNegativeUnsignedAndBadBytes() {
    CopybookCodec codec = new CopybookCodec(LAYOUT, SignEncoding.OVERPUNCH);
    assertThrows(RecordFormatException.class,
        () -> codec.encode(CopybookRecord.builder(LAYOUT).set("T-AMT", 1000).build()));
    assertThrows(RecordFormatException.class,
        () -> codec.encode(CopybookRecord.builder(LAYOUT).set("T-CNT", -1).build()));
    assertThrows(RecordFormatException.class,
        () -> CopybookRecord.builder(LAYOUT).set("T-ID", "TOO LONG"));
    assertThrows(RecordFormatException.class, () -> codec.decode(record("ABCD12x45999", PACKED, "   ")));
    assertThrows(RecordFormatException.class, () -> codec.decode(new byte[5]));
    assertThrows(RecordFormatException.class, () -> codec.decodeAll(new byte[LAYOUT.recordLength() + 1]));
    byte[] badSign = record("ABCD12345999", new byte[] {0x12, 0x34, 0x55, 0x00, 0x04, 0x2F}, "   ");
    assertThrows(RecordFormatException.class, () -> codec.decode(badSign));
  }

  @Test
  void mixedConventionsRoundTripUnderEitherCodec() {
    byte[] a = record("AB  1234{007", PACKED, "   ");
    byte[] b = record("AB  1234p007", PACKED, "   ");
    byte[] both = new byte[a.length * 2];
    System.arraycopy(a, 0, both, 0, a.length);
    System.arraycopy(b, 0, both, a.length, b.length);
    for (SignEncoding sign : SignEncoding.values()) {
      CopybookCodec codec = new CopybookCodec(LAYOUT, sign);
      List<CopybookRecord> records = codec.decodeAll(both);
      assertEquals(new BigDecimal("123.40"), records.get(0).decimal("T-AMT"));
      assertEquals(new BigDecimal("-123.40"), records.get(1).decimal("T-AMT"));
      assertArrayEquals(both, codec.encodeAll(records));
      assertEquals(Set.of(SignEncoding.OVERPUNCH, SignEncoding.GNUCOBOL), codec.signEncodingsUsed(both));
    }
  }
}
