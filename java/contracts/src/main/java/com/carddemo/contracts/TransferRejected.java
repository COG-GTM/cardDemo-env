package com.carddemo.contracts;

/** A transfer that could not be posted, with the legacy diagnostic line. */
public record TransferRejected(String tranId, String reasonCode, String detail) {
}
