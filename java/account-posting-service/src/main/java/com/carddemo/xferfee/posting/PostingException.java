package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.RejectReason;

/** A transfer that XFERFEE would abend on. The message is the legacy DISPLAY text. */
public class PostingException extends RuntimeException {

    private final String tranId;
    private final RejectReason reason;

    public PostingException(String tranId, RejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.tranId = tranId;
        this.reason = reason;
    }

    public PostingException(String tranId, RejectReason reason, String message) {
        this(tranId, reason, message, null);
    }

    public String tranId() {
        return tranId;
    }

    public RejectReason reason() {
        return reason;
    }
}
