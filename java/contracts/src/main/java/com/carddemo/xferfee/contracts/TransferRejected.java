package com.carddemo.xferfee.contracts;

/** A transfer that could not be extracted or posted. */
public record TransferRejected(
        String tranId,
        String cardNumber,
        RejectReason reason,
        String detail) {
}
