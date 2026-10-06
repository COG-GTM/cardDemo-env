package com.carddemo.xferfee.legacy.record;

/** Copybooks and dataset names of the transfer-fee chain. */
public final class LegacyCopybooks {

    public static final String DAILY_TRANSACTION = "CVTRA06Y";
    public static final String CARD_XREF = "CVACT03Y";
    public static final String ACCOUNT = "CVACT01Y";
    public static final String TRANSFER_EXTRACT = "CVXFR01Y";
    public static final String TRANSFER_FEE = "CVXFR02Y";

    public static final String HLQ = "AWS.M2.CARDDEMO";
    public static final String DALYTRAN_DSN = HLQ + ".DALYTRAN.PS";
    public static final String CARDXREF_DSN = HLQ + ".CARDXREF.PS";
    public static final String ACCTDATA_DSN = HLQ + ".ACCTDATA.PS";
    public static final String EXTRACT_DSN = HLQ + ".XFER.EXTRACT";
    public static final String ACCTDATA_XFER_DSN = HLQ + ".ACCTDATA.XFER";
    public static final String FEES_DSN = HLQ + ".XFER.FEES";
    public static final String RECON_DSN = HLQ + ".XFER.RECON.RPT";

    private LegacyCopybooks() {
    }
}
