package com.carddemo.xferfee.events;

/** {@link EventEnvelope#type()} values. */
public final class EventTypes {

    public static final String DAILY_TRANSACTION = "DailyTransaction";
    public static final String BATCH_CLOSED = "BatchClosed";
    public static final String TRANSFER_REQUESTED = "TransferRequested";
    public static final String TRANSFER_REJECTED = "TransferRejected";
    public static final String TRANSFER_POSTED = "TransferPosted";
    public static final String STEP_COMPLETED = "StepCompleted";
    public static final String RUN_COMPLETED = "RunCompleted";

    private EventTypes() {
    }
}
