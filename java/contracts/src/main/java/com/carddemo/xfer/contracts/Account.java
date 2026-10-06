package com.carddemo.xfer.contracts;

import java.math.BigDecimal;

/** ACCOUNT-RECORD (CVACT01Y) without FILLER. */
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

    public Account withPosting(BigDecimal bal, BigDecimal cycCredit, BigDecimal cycDebit) {
        return new Account(acctId, activeStatus, bal, creditLimit, cashCreditLimit, openDate,
                expirationDate, reissueDate, cycCredit, cycDebit, addrZip, groupId);
    }
}
