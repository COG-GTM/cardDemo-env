package com.carddemo.xfer.contracts;

/** Closes the daily input; equivalent to AT END on DALYTRAN. */
public record EndOfDay(String runId, long records) implements XferEvent {
}
