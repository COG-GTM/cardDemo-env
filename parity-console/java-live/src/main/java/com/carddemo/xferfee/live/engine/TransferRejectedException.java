package com.carddemo.xferfee.live.engine;

/** Thrown to roll back the posting transaction while still reporting the outcome. */
public class TransferRejectedException extends RuntimeException {

    private final transient TransferResult result;

    public TransferRejectedException(TransferResult result) {
        super(result.reason());
        this.result = result;
    }

    public TransferResult result() {
        return result;
    }
}
