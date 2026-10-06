package com.carddemo.xfer.legacy;

import com.carddemo.xfer.contracts.TransferPosted;

/** XFERFEE, CVXFR02Y (RECLN 100). */
public final class FeeRecordCodec {

    public static final int LENGTH = 100;

    private FeeRecordCodec() {
    }

    public static byte[] encode(TransferPosted p) {
        byte[] r = new byte[LENGTH];
        Cobol.putText(r, 0, 16, p.tranId());
        Cobol.putText(r, 16, 10, p.tranDt());
        Cobol.putUnsigned(r, 26, 11, p.srcAcctId());
        Cobol.putUnsigned(r, 37, 11, p.tgtAcctId());
        Cobol.putText(r, 48, 10, p.bookId());
        Cobol.putPacked(r, 58, 6, 2, p.tranAmt());
        Cobol.putPacked(r, 64, 4, 6, p.feePct());
        Cobol.putPacked(r, 68, 6, 2, p.feeAmt());
        Cobol.putText(r, 74, 1, p.capApplied() ? "Y" : "N");
        Cobol.putText(r, 75, 10, p.ruleEffDt());
        return r;
    }
}
