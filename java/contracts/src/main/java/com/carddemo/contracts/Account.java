package com.carddemo.contracts;

import java.math.BigDecimal;

/** Account master row (copybook CVACT01Y). Dates stay text because the legacy fields are PIC X(10). */
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
