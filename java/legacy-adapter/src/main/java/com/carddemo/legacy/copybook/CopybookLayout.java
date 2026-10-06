package com.carddemo.legacy.copybook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fixed-length record layout derived from a copybook. */
public final class CopybookLayout {
  private final String name;
  private final List<CopybookField> fields;
  private final Map<String, CopybookField> byName = new LinkedHashMap<>();
  private final int recordLength;

  public CopybookLayout(String name, List<CopybookField> fields) {
    this.name = name;
    this.fields = List.copyOf(fields);
    int length = 0;
    for (CopybookField field : this.fields) {
      if (!field.isFiller() && byName.putIfAbsent(field.name(), field) != null) {
        throw new IllegalArgumentException(name + ": duplicate field " + field.name());
      }
      length = Math.max(length, field.end());
    }
    this.recordLength = length;
  }

  public String name() {
    return name;
  }

  public List<CopybookField> fields() {
    return fields;
  }

  /** Named (non-FILLER) fields in record order. */
  public List<CopybookField> dataFields() {
    return fields.stream().filter(f -> !f.isFiller()).toList();
  }

  public Optional<CopybookField> field(String fieldName) {
    return Optional.ofNullable(byName.get(fieldName));
  }

  public CopybookField requireField(String fieldName) {
    return field(fieldName)
        .orElseThrow(() -> new IllegalArgumentException(name + ": no field " + fieldName));
  }

  public int recordLength() {
    return recordLength;
  }

  @Override
  public String toString() {
    return "CopybookLayout[" + name + ", " + recordLength + " bytes, " + fields.size() + " fields]";
  }
}
