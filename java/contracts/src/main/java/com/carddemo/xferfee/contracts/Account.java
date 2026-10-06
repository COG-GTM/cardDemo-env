package com.carddemo.xferfee.contracts;

import java.math.BigDecimal;

/**
 * One CVACT01Y account master record. Dates stay as the raw 10-byte strings because
 * the legacy master allows blanks.
 *
 * @param accountId          ACCT-ID
 * @param activeStatus       ACCT-ACTIVE-STATUS
 * @param currentBalance     ACCT-CURR-BAL
 * @param creditLimit        ACCT-CREDIT-LIMIT
 * @param cashCreditLimit    ACCT-CASH-CREDIT-LIMIT
 * @param openDate           ACCT-OPEN-DATE
 * @param expirationDate     ACCT-EXPIRAION-DATE
 * @param reissueDate        ACCT-REISSUE-DATE
 * @param currentCycleCredit ACCT-CURR-CYC-CREDIT
 * @param currentCycleDebit  ACCT-CURR-CYC-DEBIT
 * @param addressZip         ACCT-ADDR-ZIP
 * @param groupId            ACCT-GROUP-ID (the fee book)
 */
public record Account(
        long accountId,
        String activeStatus,
        BigDecimal currentBalance,
        BigDecimal creditLimit,
        BigDecimal cashCreditLimit,
        String openDate,
        String expirationDate,
        String reissueDate,
        BigDecimal currentCycleCredit,
        BigDecimal currentCycleDebit,
        String addressZip,
        String groupId) {
}
