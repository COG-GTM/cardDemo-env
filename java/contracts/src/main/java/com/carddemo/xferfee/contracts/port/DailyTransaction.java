package com.carddemo.xferfee.contracts.port;

import java.math.BigDecimal;

/** One CVTRA05Y DALYTRAN record, text fields with trailing blanks trimmed. */
public record DailyTransaction(
        String tranId,
        String typeCode,
        int categoryCode,
        String source,
        String description,
        BigDecimal amount,
        long merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String cardNumber,
        String origTimestamp,
        String procTimestamp) {
}
