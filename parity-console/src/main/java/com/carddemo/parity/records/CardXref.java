package com.carddemo.parity.records;

/** CVACT03Y card cross-reference record (50 bytes). */
public record CardXref(String cardNumber, long accountId) {

    public static final int LENGTH = 50;

    public static CardXref parse(byte[] record) {
        return new CardXref(Cobol.text(record, 0, 16), Cobol.unsigned(record, 25, 11));
    }
}
