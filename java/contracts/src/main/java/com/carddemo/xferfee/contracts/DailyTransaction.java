package com.carddemo.xferfee.contracts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/** Daily transaction record (CVTRA05Y), as read from {@code DALYTRAN.PS}. Timestamps are raw text. */
public record DailyTransaction(
        String tranId,
        String typeCode,
        int categoryCode,
        String source,
        String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        long merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String cardNumber,
        String originTimestamp,
        String processTimestamp) {
}
