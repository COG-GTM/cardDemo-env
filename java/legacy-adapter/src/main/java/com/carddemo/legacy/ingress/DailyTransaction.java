package com.carddemo.legacy.ingress;

import com.carddemo.legacy.codec.CopybookRecord;
import java.math.BigDecimal;

/** One {@code DALYTRAN.PS} record (copybook CVTRA06Y), as published to {@code card.transactions}. */
public record DailyTransaction(
    String tranId,
    String typeCd,
    int catCd,
    String source,
    String desc,
    BigDecimal amount,
    long merchantId,
    String merchantName,
    String merchantCity,
    String merchantZip,
    String cardNum,
    String origTs,
    String procTs) {

  public static final String COPYBOOK = "CVTRA06Y";

  public static DailyTransaction from(CopybookRecord r) {
    return new DailyTransaction(
        r.string("DALYTRAN-ID"),
        r.string("DALYTRAN-TYPE-CD"),
        r.decimal("DALYTRAN-CAT-CD").intValueExact(),
        r.string("DALYTRAN-SOURCE"),
        r.string("DALYTRAN-DESC"),
        r.decimal("DALYTRAN-AMT"),
        r.longValue("DALYTRAN-MERCHANT-ID"),
        r.string("DALYTRAN-MERCHANT-NAME"),
        r.string("DALYTRAN-MERCHANT-CITY"),
        r.string("DALYTRAN-MERCHANT-ZIP"),
        r.string("DALYTRAN-CARD-NUM"),
        r.string("DALYTRAN-ORIG-TS"),
        r.string("DALYTRAN-PROC-TS"));
  }
}
