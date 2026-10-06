package com.carddemo.xfer.contracts;

/** Kafka topics; each has one partition so a run is consumed in file order. */
public final class Topics {

    public static final String DAILY_TRAN = "xfer.daily-tran";
    public static final String TRANSFER_REQUESTED = "xfer.transfer-requested";
    public static final String TRANSFER_POSTED = "xfer.transfer-posted";

    private Topics() {
    }
}
