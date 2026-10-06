package com.carddemo.xfer.legacy;

import com.carddemo.xfer.contracts.TransferRequested;

/** XFEREXTR, CVXFR01Y (RECLN 120). */
public final class ExtractCodec {

    public static final int LENGTH = 120;

    private ExtractCodec() {
    }

    public static byte[] encode(TransferRequested t) {
        byte[] r = new byte[LENGTH];
        Cobol.putText(r, 0, 16, t.tranId());
        Cobol.putText(r, 16, 10, t.tranDt());
        Cobol.putUnsigned(r, 26, 11, t.srcAcctId());
        Cobol.putUnsigned(r, 37, 11, t.tgtAcctId());
        Cobol.putText(r, 48, 10, t.bookId());
        Cobol.putZoned(r, 58, 11, 2, t.tranAmt());
        Cobol.putText(r, 69, 16, t.cardNum());
        return r;
    }
}
