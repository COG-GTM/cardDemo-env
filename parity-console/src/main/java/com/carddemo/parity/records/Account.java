package com.carddemo.parity.records;

import java.math.BigDecimal;

/** CVACT01Y account master record (300 bytes). Untouched bytes are carried through verbatim. */
public final class Account {

    public static final int LENGTH = 300;
    private static final int ID = 0;
    private static final int CURR_BAL = 12;
    private static final int CYC_CREDIT = 78;
    private static final int CYC_DEBIT = 90;
    private static final int GROUP_ID = 112;
    private static final int DATA_LENGTH = 122;

    private final byte[] raw;
    private BigDecimal balance;
    private BigDecimal cycleCredit;
    private BigDecimal cycleDebit;
    private boolean balanceTouched;
    private boolean creditTouched;
    private boolean debitTouched;

    public Account(byte[] record) {
        this.raw = record.clone();
        this.balance = Cobol.zoned(raw, CURR_BAL, 12, 2);
        this.cycleCredit = Cobol.zoned(raw, CYC_CREDIT, 12, 2);
        this.cycleDebit = Cobol.zoned(raw, CYC_DEBIT, 12, 2);
    }

    public Account copy() {
        return new Account(toBytes());
    }

    public long id() {
        return Cobol.unsigned(raw, ID, 11);
    }

    public String book() {
        return Cobol.text(raw, GROUP_ID, 10);
    }

    public BigDecimal balance() {
        return balance;
    }

    public BigDecimal cycleCredit() {
        return cycleCredit;
    }

    public BigDecimal cycleDebit() {
        return cycleDebit;
    }

    public void debit(BigDecimal amount, BigDecimal fee) {
        balance = balance.subtract(amount).subtract(fee);
        cycleDebit = cycleDebit.add(amount).add(fee);
        balanceTouched = true;
        debitTouched = true;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
        cycleCredit = cycleCredit.add(amount);
        balanceTouched = true;
        creditTouched = true;
    }

    /** XFERFEE 3000-WRITE-MASTER: fields re-moved from working storage, FILLER left low-values. */
    public byte[] toBytes() {
        byte[] out = new byte[LENGTH];
        Cobol.copy(raw, 0, out, 0, DATA_LENGTH);
        if (balanceTouched) {
            Cobol.putSignedZoned(out, CURR_BAL, 12, 2, balance);
        }
        if (creditTouched) {
            Cobol.putSignedZoned(out, CYC_CREDIT, 12, 2, cycleCredit);
        }
        if (debitTouched) {
            Cobol.putSignedZoned(out, CYC_DEBIT, 12, 2, cycleDebit);
        }
        return out;
    }
}
