package com.carddemo.xfer.posting;

/** Decision register D1: batch-atomic is the parity mode, per-transfer the target mode. */
public enum PostingMode {
    BATCH_ATOMIC,
    PER_TRANSFER;

    public static PostingMode parse(String value) {
        return valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
