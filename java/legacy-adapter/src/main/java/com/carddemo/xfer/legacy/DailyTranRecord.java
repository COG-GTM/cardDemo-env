package com.carddemo.xfer.legacy;

import java.math.BigDecimal;

/** DALYTRAN, CVTRA05Y (RECLN 350): only the fields CBXFR01C reads. */
public record DailyTranRecord(
        String tranId,
        String tranTypeCd,
        String tranDesc,
        BigDecimal tranAmt,
        String tranCardNum,
        String tranOrigTs) {

    public static final int LENGTH = 350;

    public static DailyTranRecord decode(byte[] r) {
        return new DailyTranRecord(
                Cobol.text(r, 0, 16),
                Cobol.text(r, 16, 2),
                Cobol.text(r, 32, 100),
                Cobol.zoned(r, 132, 11, 2),
                Cobol.text(r, 262, 16),
                Cobol.text(r, 278, 26));
    }
}
