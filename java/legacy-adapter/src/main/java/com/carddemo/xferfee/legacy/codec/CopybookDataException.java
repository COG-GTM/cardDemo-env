package com.carddemo.xferfee.legacy.codec;

/** Raised when bytes do not match a copybook picture, or a value cannot be represented in one. */
public class CopybookDataException extends RuntimeException {

    public CopybookDataException(String message) {
        super(message);
    }

    public CopybookDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
