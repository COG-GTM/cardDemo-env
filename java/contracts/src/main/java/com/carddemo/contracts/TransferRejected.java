package com.carddemo.contracts;

/** A transfer the chain could not process; the legacy RC 4 "unmatched" path. */
public record TransferRejected(
        String tranId,
        String cardNum,
        String step,
        String program,
        RejectReason reason,
        int returnCode,
        String detail) {
}
