package com.carddemo.xfer.legacy;

import com.carddemo.xfer.contracts.Account;
import java.util.Arrays;

/** ACCTFILE / ACCTOUT, CVACT01Y (RECLN 300). */
public final class AccountCodec {

    public static final int LENGTH = 300;
    private static final int FILLER = 122;

    private AccountCodec() {
    }

    public static Account decode(byte[] r) {
        return new Account(
                Cobol.unsigned(r, 0, 11),
                Cobol.text(r, 11, 1),
                Cobol.zoned(r, 12, 12, 2),
                Cobol.zoned(r, 24, 12, 2),
                Cobol.zoned(r, 36, 12, 2),
                Cobol.text(r, 48, 10),
                Cobol.text(r, 58, 10),
                Cobol.text(r, 68, 10),
                Cobol.zoned(r, 78, 12, 2),
                Cobol.zoned(r, 90, 12, 2),
                Cobol.text(r, 102, 10),
                Cobol.text(r, 112, 10));
    }

    public static byte[] encode(Account a) {
        byte[] r = new byte[LENGTH];
        Cobol.putUnsigned(r, 0, 11, a.acctId());
        Cobol.putText(r, 11, 1, a.activeStatus());
        Cobol.putZoned(r, 12, 12, 2, a.currBal());
        Cobol.putZoned(r, 24, 12, 2, a.creditLimit());
        Cobol.putZoned(r, 36, 12, 2, a.cashCreditLimit());
        Cobol.putText(r, 48, 10, a.openDate());
        Cobol.putText(r, 58, 10, a.expirationDate());
        Cobol.putText(r, 68, 10, a.reissueDate());
        Cobol.putZoned(r, 78, 12, 2, a.currCycCredit());
        Cobol.putZoned(r, 90, 12, 2, a.currCycDebit());
        Cobol.putText(r, 102, 10, a.addrZip());
        Cobol.putText(r, 112, 10, a.groupId());
        return r;
    }

    /**
     * XFERFEE 3000-WRITE-MASTER: the record is moved back field by field, so fields keep
     * their input bytes unless 2100-POST-TRANSFER ran ADD/SUBTRACT on them (even by zero),
     * and FILLER (never moved) stays LOW-VALUES.
     *
     * @param source the account was XFR-SRC-ACCT-ID of a posted transfer (BAL, CYC-DEBIT)
     * @param target the account was XFR-TGT-ACCT-ID of a posted transfer (BAL, CYC-CREDIT)
     */
    public static byte[] rewrite(byte[] original, Account a, boolean source, boolean target) {
        if (original == null) {
            return encode(a);
        }
        byte[] r = Arrays.copyOf(original, LENGTH);
        Arrays.fill(r, FILLER, LENGTH, (byte) 0);
        if (source || target) {
            Cobol.putZonedComputed(r, 12, 12, 2, a.currBal());
        }
        if (target) {
            Cobol.putZonedComputed(r, 78, 12, 2, a.currCycCredit());
        }
        if (source) {
            Cobol.putZonedComputed(r, 90, 12, 2, a.currCycDebit());
        }
        return r;
    }
}
