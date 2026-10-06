package com.carddemo.xfer.legacy;

/** XREFFILE, CVACT03Y (RECLN 50). */
public record CardXrefRecord(String cardNum, long custId, long acctId) {

    public static final int LENGTH = 50;

    public static CardXrefRecord decode(byte[] r) {
        return new CardXrefRecord(
                Cobol.text(r, 0, 16),
                Cobol.unsigned(r, 16, 9),
                Cobol.unsigned(r, 25, 11));
    }
}
