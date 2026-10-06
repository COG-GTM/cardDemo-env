package com.carddemo.xfer.contracts;

/** Extract step finished; {@code rc} is the CBXFR01C return code. */
public record ExtractClosed(String runId, long selected, int rc) implements XferEvent {
}
