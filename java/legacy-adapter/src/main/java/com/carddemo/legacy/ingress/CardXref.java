package com.carddemo.legacy.ingress;

import com.carddemo.legacy.codec.CopybookRecord;

/** One card cross-reference record (copybook CVACT03Y). */
public record CardXref(String cardNum, long custId, long acctId) {

  public static final String COPYBOOK = "CVACT03Y";

  public static CardXref from(CopybookRecord r) {
    return new CardXref(
        r.string("XREF-CARD-NUM"), r.longValue("XREF-CUST-ID"), r.longValue("XREF-ACCT-ID"));
  }
}
