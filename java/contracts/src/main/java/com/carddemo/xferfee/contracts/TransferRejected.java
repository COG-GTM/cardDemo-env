package com.carddemo.xferfee.contracts;

/** A transfer that was not posted, with the legacy reason (e.g. unmatched card, no fee rule). */
public record TransferRejected(
        String tranId,
        String cardNum,
        String reasonCode,
        String reason) {
}
