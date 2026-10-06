package com.carddemo.xferfee.posting;

/**
 * How a run of transfers is committed.
 *
 * <p>{@link #BATCH_ATOMIC} reproduces XFERFEE: one commit for the whole run, any failure rolls
 * everything back and ends RC 8 (BR-15). {@link #PER_TRANSFER} commits each transfer on its own
 * and parks failures as {@code TransferRejected}; it is the intended target default pending
 * decision-register item D1 (COG-1249).
 */
public enum PostingMode {
    PER_TRANSFER,
    BATCH_ATOMIC
}
