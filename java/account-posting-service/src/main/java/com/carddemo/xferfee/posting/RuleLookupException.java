package com.carddemo.xferfee.posting;

/**
 * A {@code FeeSchedule} lookup that XFERFEE's single-row {@code SELECT ... INTO} would fail with a non-zero
 * SQLCODE (e.g. -811 for more than one matching CTL_XFER_PARM row).
 */
public class RuleLookupException extends RuntimeException {

    public static final int MULTIPLE_ROWS = -811;

    private final int sqlCode;

    public RuleLookupException(int sqlCode, String detail) {
        super(detail);
        this.sqlCode = sqlCode;
    }

    public int sqlCode() {
        return sqlCode;
    }
}
