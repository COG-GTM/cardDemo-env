package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;

/** Account master row (copybook CVACT01Y). */
public record Account(
        long acctId,
        String activeStatus,
        BigDecimal currBal,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        BigDecimal currCycCredit,
        BigDecimal currCycDebit,
        String addrZip,
        String groupId) {
}
