package com.carddemo.xferfee.intake;

/**
 * A transfer CBXFR01C would copy through byte-for-byte but the {@code TransferRequested} contract cannot hold
 * (non-numeric target account, non-date business date). See the decision register (COG-1249).
 */
public class UnrepresentableTransferException extends RuntimeException {

    private final String tranId;

    public UnrepresentableTransferException(String tranId, String message) {
        super(tranId + ": " + message);
        this.tranId = tranId;
    }

    public String tranId() {
        return tranId;
    }
}
