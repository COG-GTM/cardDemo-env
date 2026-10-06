package com.carddemo.xfer.contracts;

/** Per-transfer mode only: a transfer parked instead of aborting the run. */
public record TransferRejected(String runId, long seq, String tranId, String reason)
        implements XferEvent {
}
