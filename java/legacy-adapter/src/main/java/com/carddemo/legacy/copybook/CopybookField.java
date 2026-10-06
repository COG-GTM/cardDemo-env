package com.carddemo.legacy.copybook;

/**
 * One elementary item of a copybook layout. {@code OCCURS} items are expanded into one field per
 * occurrence named {@code NAME[i]}.
 *
 * @param name COBOL data name ({@code FILLER} for unnamed storage)
 * @param offset zero-based byte offset within the record
 * @param length storage length in bytes
 * @param kind storage class
 * @param digits number of digit positions (numeric) or characters (alphanumeric)
 * @param scale implied decimal places ({@code V}); zero for alphanumeric
 * @param signed whether the picture carries {@code S}
 */
public record CopybookField(
    String name, int offset, int length, FieldKind kind, int digits, int scale, boolean signed) {

  public boolean isFiller() {
    return "FILLER".equals(name);
  }

  public boolean isNumeric() {
    return kind != FieldKind.ALPHANUMERIC;
  }

  public int end() {
    return offset + length;
  }
}
