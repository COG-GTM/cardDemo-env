package com.carddemo.legacy.codec;

import com.carddemo.legacy.copybook.CopybookField;
import com.carddemo.legacy.copybook.CopybookLayout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decoded record: named field values ({@link String} for alphanumeric, {@link BigDecimal} for numeric)
 * plus the bytes it was decoded from. Re-encoding keeps the original bytes of FILLER and of every
 * field whose value was not changed, so a round trip is lossless even when a legacy writer mixed
 * zoned sign conventions within one record.
 */
public final class CopybookRecord {
  private final CopybookLayout layout;
  private final Map<String, Object> values;
  private final byte[] image;
  private final Map<String, Object> imageValues;

  private CopybookRecord(
      CopybookLayout layout, Map<String, Object> values, byte[] image, Map<String, Object> imageValues) {
    this.layout = layout;
    this.values = values;
    this.image = image;
    this.imageValues = imageValues;
  }

  static CopybookRecord decoded(CopybookLayout layout, Map<String, Object> values, byte[] image) {
    Map<String, Object> frozen = Collections.unmodifiableMap(values);
    return new CopybookRecord(layout, frozen, image, frozen);
  }

  /** Starts a new record with all fields blank/zero and FILLER set to spaces. */
  public static Builder builder(CopybookLayout layout) {
    byte[] image = new byte[layout.recordLength()];
    Arrays.fill(image, (byte) ' ');
    Map<String, Object> values = new LinkedHashMap<>();
    for (CopybookField field : layout.dataFields()) {
      values.put(field.name(), field.isNumeric() ? BigDecimal.ZERO.setScale(field.scale()) : "");
    }
    return new Builder(layout, values, image, Map.of());
  }

  public CopybookLayout layout() {
    return layout;
  }

  public Map<String, Object> values() {
    return values;
  }

  public String string(String field) {
    layout.requireField(field);
    return (String) values.get(field);
  }

  public BigDecimal decimal(String field) {
    layout.requireField(field);
    return (BigDecimal) values.get(field);
  }

  public long longValue(String field) {
    return decimal(field).longValueExact();
  }

  /** Bytes the record was decoded from (or the blank image for built records). */
  byte[] image() {
    return image;
  }

  /** True if {@code field} still holds the value decoded from {@link #image()}. */
  boolean unchangedFromImage(String field) {
    return imageValues.containsKey(field) && imageValues.get(field).equals(values.get(field));
  }

  public Builder toBuilder() {
    return new Builder(layout, new LinkedHashMap<>(values), image.clone(), imageValues);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CopybookRecord other
        && layout.name().equals(other.layout.name())
        && values.equals(other.values);
  }

  @Override
  public int hashCode() {
    return Objects.hash(layout.name(), values);
  }

  @Override
  public String toString() {
    return layout.name() + values;
  }

  /** Mutable builder; values are validated against the layout when set. */
  public static final class Builder {
    private final CopybookLayout layout;
    private final Map<String, Object> values;
    private final byte[] image;
    private final Map<String, Object> imageValues;

    private Builder(
        CopybookLayout layout, Map<String, Object> values, byte[] image, Map<String, Object> imageValues) {
      this.layout = layout;
      this.values = values;
      this.image = image;
      this.imageValues = imageValues;
    }

    public Builder set(String fieldName, Object value) {
      CopybookField field = layout.requireField(fieldName);
      Objects.requireNonNull(value, fieldName);
      if (field.isNumeric()) {
        BigDecimal number =
            value instanceof BigDecimal d ? d : new BigDecimal(value.toString().trim());
        values.put(fieldName, number.setScale(field.scale(), RoundingMode.DOWN));
      } else {
        String text = value.toString();
        if (text.length() > field.length()) {
          throw new RecordFormatException(
              fieldName + ": value longer than " + field.length() + " characters");
        }
        values.put(fieldName, stripTrailingSpaces(text));
      }
      return this;
    }

    public CopybookRecord build() {
      return new CopybookRecord(
          layout, Collections.unmodifiableMap(new LinkedHashMap<>(values)), image.clone(), imageValues);
    }
  }

  static String stripTrailingSpaces(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(0, end);
  }
}
