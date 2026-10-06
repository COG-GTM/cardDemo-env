package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;

/** One DALYTRAN record (copybook CVTRA05Y) as streamed by the console. */
public record DailyTransaction(
        String tranId,
        String typeCd,
        String cardNum,
        String desc,
        BigDecimal amount,
        String origTs) {
}
