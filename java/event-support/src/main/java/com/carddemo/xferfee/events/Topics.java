package com.carddemo.xferfee.events;

import java.util.List;

/** Kafka topics of the event-mode chain, in flow order. */
public final class Topics {

    /** Ingress: {@code DailyTransaction}* then {@code BatchClosed} (replaces DALYTRAN.PS). */
    public static final String DAILY_TRANSACTIONS = "xferfee.daily-transactions";
    /** transfer-intake-service: {@code TransferRequested}* then {@code StepCompleted(STEP010)}. */
    public static final String TRANSFER_REQUESTED = "xferfee.transfer-requested";
    /** account-posting-service: {@code TransferPosted}* then {@code StepCompleted(STEP020)}. */
    public static final String TRANSFER_POSTED = "xferfee.transfer-posted";
    /** Dead-letter topic: {@code TransferRejected} from intake and posting. */
    public static final String TRANSFER_REJECTED = "xferfee.transfer-rejected";
    /** reconciliation-service: {@code StepCompleted(STEP030)} and {@code RunCompleted}. */
    public static final String RUN_STATUS = "xferfee.run-status";

    public static final List<String> ALL = List.of(
            DAILY_TRANSACTIONS, TRANSFER_REQUESTED, TRANSFER_POSTED, TRANSFER_REJECTED, RUN_STATUS);

    public static final int PARTITIONS = 3;

    private Topics() {
    }
}
