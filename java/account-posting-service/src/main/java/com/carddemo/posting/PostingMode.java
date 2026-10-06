package com.carddemo.posting;

public enum PostingMode {
    /** Each transfer commits on its own; a failed transfer is rejected and the run continues. */
    PER_TRANSFER,
    /** Legacy XFERFEE semantics: one commit for the whole run, any failure rolls everything back (RC 8). */
    BATCH_ATOMIC
}
