package com.carddemo.parity.records;

import java.math.BigDecimal;

/** CVTRA05Y daily transaction record (350 bytes). */
public record DailyTransaction(byte[] raw) {

    public static final int LENGTH = 350;

    public String tranId() {
        return Cobol.text(raw, 0, 16);
    }

    public String typeCode() {
        return Cobol.text(raw, 16, 2);
    }

    public String description() {
        return Cobol.text(raw, 32, 100).stripTrailing();
    }

    /** TRAN-DESC(14:11): the target account embedded in "XFER TO ACCT nnnnnnnnnnn". */
    public byte[] targetAccountField() {
        byte[] field = new byte[11];
        Cobol.copy(raw, 32 + 13, field, 0, 11);
        return field;
    }

    public byte[] amountField() {
        byte[] field = new byte[11];
        Cobol.copy(raw, 132, field, 0, 11);
        return field;
    }

    public BigDecimal amount() {
        return Cobol.zoned(raw, 132, 11, 2);
    }

    public String cardNumber() {
        return Cobol.text(raw, 262, 16);
    }

    public String origTimestamp() {
        return Cobol.text(raw, 278, 26);
    }
}
