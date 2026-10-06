package com.carddemo.legacy.codec;

import com.carddemo.legacy.copybook.CopybookField;
import com.carddemo.legacy.copybook.CopybookLayout;
import com.carddemo.legacy.copybook.FieldKind;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Byte-exact codec between fixed-length ASCII records and {@link CopybookRecord}s.
 *
 * <p>Decoding accepts both zoned sign conventions ({@link SignEncoding}), per field. Encoding writes
 * changed and newly built fields with this codec's convention and carries FILLER and unchanged
 * fields over from the decoded bytes, so decode followed by encode is always byte-identical.
 */
public final class CopybookCodec {
  private final CopybookLayout layout;
  private final SignEncoding signEncoding;

  public CopybookCodec(CopybookLayout layout, SignEncoding signEncoding) {
    this.layout = layout;
    this.signEncoding = signEncoding;
  }

  public CopybookLayout layout() {
    return layout;
  }

  public SignEncoding signEncoding() {
    return signEncoding;
  }

  public CopybookRecord decode(byte[] record) {
    if (record.length != layout.recordLength()) {
      throw new RecordFormatException(
          layout.name() + ": expected " + layout.recordLength() + " bytes, got " + record.length);
    }
    return decodeAt(record, 0);
  }

  public byte[] encode(CopybookRecord record) {
    if (!record.layout().name().equals(layout.name())) {
      throw new IllegalArgumentException(
          "record layout " + record.layout().name() + " does not match codec " + layout.name());
    }
    byte[] out = record.image().clone();
    for (CopybookField field : layout.dataFields()) {
      if (record.unchangedFromImage(field.name())) {
        continue;
      }
      Object value = record.values().get(field.name());
      switch (field.kind()) {
        case ALPHANUMERIC -> writeText(field, (String) value, out);
        case ZONED -> ZonedDecimal.encode(field, (BigDecimal) value, signEncoding, out, field.offset());
        case PACKED -> PackedDecimal.encode(field, (BigDecimal) value, out, field.offset());
      }
    }
    return out;
  }

  /** Decodes a dataset of back-to-back fixed-length records. */
  public List<CopybookRecord> decodeAll(byte[] dataset) {
    int length = layout.recordLength();
    if (dataset.length % length != 0) {
      throw new RecordFormatException(
          layout.name() + ": dataset of " + dataset.length + " bytes is not a multiple of " + length);
    }
    List<CopybookRecord> records = new ArrayList<>(dataset.length / length);
    for (int offset = 0; offset < dataset.length; offset += length) {
      records.add(decodeAt(dataset, offset));
    }
    return records;
  }

  public byte[] encodeAll(List<CopybookRecord> records) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(records.size() * layout.recordLength());
    for (CopybookRecord record : records) {
      out.writeBytes(encode(record));
    }
    return out.toByteArray();
  }

  /**
   * Zoned sign conventions observed in a dataset's signed DISPLAY fields. A plain trailing digit is
   * attributed to {@link SignEncoding#GNUCOBOL}; mainframe overpunch never produces one.
   */
  public Set<SignEncoding> signEncodingsUsed(byte[] dataset) {
    Set<SignEncoding> seen = EnumSet.noneOf(SignEncoding.class);
    int length = layout.recordLength();
    for (int offset = 0; offset + length <= dataset.length; offset += length) {
      for (CopybookField field : layout.dataFields()) {
        if (field.kind() == FieldKind.ZONED && field.signed()) {
          SignEncoding evidence = ZonedDecimal.decode(field, dataset, offset + field.offset()).evidence();
          seen.add(evidence == null ? SignEncoding.GNUCOBOL : evidence);
        }
      }
    }
    return seen;
  }

  private CopybookRecord decodeAt(byte[] data, int offset) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (CopybookField field : layout.dataFields()) {
      int at = offset + field.offset();
      Object value =
          switch (field.kind()) {
            case ALPHANUMERIC -> CopybookRecord.stripTrailingSpaces(
                new String(data, at, field.length(), StandardCharsets.ISO_8859_1));
            case ZONED -> ZonedDecimal.decode(field, data, at).value();
            case PACKED -> PackedDecimal.decode(field, data, at);
          };
      values.put(field.name(), value);
    }
    return CopybookRecord.decoded(
        layout, values, Arrays.copyOfRange(data, offset, offset + layout.recordLength()));
  }

  private static void writeText(CopybookField field, String value, byte[] out) {
    byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
    if (bytes.length > field.length()) {
      throw new RecordFormatException(field.name() + ": value longer than " + field.length() + " characters");
    }
    System.arraycopy(bytes, 0, out, field.offset(), bytes.length);
    Arrays.fill(out, field.offset() + bytes.length, field.end(), (byte) ' ');
  }
}
