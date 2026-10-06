package com.carddemo.legacy.ingress;

import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.copybook.CopybookLayout;
import java.math.BigDecimal;

/** One account master record (copybook CVACT01Y). */
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

  public static final String COPYBOOK = "CVACT01Y";

  public static Account from(CopybookRecord r) {
    return new Account(
        r.longValue("ACCT-ID"),
        r.string("ACCT-ACTIVE-STATUS"),
        r.decimal("ACCT-CURR-BAL"),
        r.decimal("ACCT-CREDIT-LIMIT"),
        r.decimal("ACCT-CASH-CREDIT-LIMIT"),
        r.string("ACCT-OPEN-DATE"),
        r.string("ACCT-EXPIRAION-DATE"),
        r.string("ACCT-REISSUE-DATE"),
        r.decimal("ACCT-CURR-CYC-CREDIT"),
        r.decimal("ACCT-CURR-CYC-DEBIT"),
        r.string("ACCT-ADDR-ZIP"),
        r.string("ACCT-GROUP-ID"));
  }

  /** New CVACT01Y record with FILLER blank. */
  public CopybookRecord toRecord(CopybookLayout layout) {
    return applyTo(CopybookRecord.builder(layout));
  }

  /** Overlays this account on its source record; unchanged fields keep their original bytes. */
  public CopybookRecord applyTo(CopybookRecord source) {
    if (source.longValue("ACCT-ID") != acctId) {
      throw new IllegalArgumentException("source record is account " + source.longValue("ACCT-ID") + ", not " + acctId);
    }
    return applyTo(source.toBuilder());
  }

  private CopybookRecord applyTo(CopybookRecord.Builder builder) {
    return builder
        .set("ACCT-ID", acctId)
        .set("ACCT-ACTIVE-STATUS", activeStatus)
        .set("ACCT-CURR-BAL", currBal)
        .set("ACCT-CREDIT-LIMIT", creditLimit)
        .set("ACCT-CASH-CREDIT-LIMIT", cashCreditLimit)
        .set("ACCT-OPEN-DATE", openDate)
        .set("ACCT-EXPIRAION-DATE", expirationDate)
        .set("ACCT-REISSUE-DATE", reissueDate)
        .set("ACCT-CURR-CYC-CREDIT", currCycCredit)
        .set("ACCT-CURR-CYC-DEBIT", currCycDebit)
        .set("ACCT-ADDR-ZIP", addrZip)
        .set("ACCT-GROUP-ID", groupId)
        .build();
  }
}
