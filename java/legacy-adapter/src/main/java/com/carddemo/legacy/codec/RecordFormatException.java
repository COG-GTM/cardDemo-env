package com.carddemo.legacy.codec;

/** Raised when bytes cannot be decoded, or a value cannot be encoded, under a copybook layout. */
public final class RecordFormatException extends RuntimeException {
  public RecordFormatException(String message) {
    super(message);
  }
}
