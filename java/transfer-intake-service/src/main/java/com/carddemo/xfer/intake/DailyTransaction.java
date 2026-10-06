package com.carddemo.xfer.intake;

import java.math.BigDecimal;

/**
 * The CVTRA05Y fields CBXFR01C reads. Text fields keep their fixed-width value.
 *
 * @param tranId          TRAN-ID, X(16)
 * @param typeCode        TRAN-TYPE-CD, X(02)
 * @param description     TRAN-DESC, X(100)
 * @param amount          TRAN-AMT, S9(09)V99
 * @param cardNumber      TRAN-CARD-NUM, X(16)
 * @param originTimestamp TRAN-ORIG-TS, X(26)
 */
public record DailyTransaction(
        String tranId,
        String typeCode,
        String description,
        BigDecimal amount,
        String cardNumber,
        String originTimestamp) {
}
