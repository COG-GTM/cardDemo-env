package com.carddemo.xfer.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.carddemo.xfer.contracts.Account;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountCodecTest {

    private static byte[] original() {
        byte[] r = new byte[AccountCodec.LENGTH];
        java.util.Arrays.fill(r, (byte) ' ');
        Cobol.putUnsigned(r, 0, 11, 1L);
        Cobol.putText(r, 11, 1, "Y");
        Cobol.putZoned(r, 12, 12, 2, new BigDecimal("500.00"));
        Cobol.putZoned(r, 24, 12, 2, new BigDecimal("1000.00"));
        Cobol.putZoned(r, 36, 12, 2, new BigDecimal("100.00"));
        Cobol.putZoned(r, 78, 12, 2, BigDecimal.ZERO);
        Cobol.putZoned(r, 90, 12, 2, BigDecimal.ZERO);
        Cobol.putText(r, 122, 6, "FILLER");
        return r;
    }

    @Test
    void untouchedAccountKeepsInputFieldsAndClearsFiller() {
        byte[] r = original();
        byte[] out = AccountCodec.rewrite(r, AccountCodec.decode(r), false, false);
        assertArrayEquals(java.util.Arrays.copyOf(r, 122), java.util.Arrays.copyOf(out, 122));
        assertArrayEquals(new byte[178], java.util.Arrays.copyOfRange(out, 122, 300));
    }

    @Test
    void changedBalancesUseGnuCobolArithmeticSigns() {
        byte[] r = original();
        Account a = AccountCodec.decode(r);
        Account posted = new Account(a.acctId(), a.activeStatus(), new BigDecimal("-10.00"),
                a.creditLimit(), a.cashCreditLimit(), a.openDate(), a.expirationDate(),
                a.reissueDate(), a.currCycCredit(), new BigDecimal("510.00"), a.addrZip(), a.groupId());
        byte[] out = AccountCodec.rewrite(r, posted, true, false);
        assertEquals("00000000100p", new String(out, 12, 12, StandardCharsets.US_ASCII));
        assertEquals("000000051000", new String(out, 90, 12, StandardCharsets.US_ASCII));
        assertEquals(new String(r, 24, 12, StandardCharsets.US_ASCII),
                new String(out, 24, 12, StandardCharsets.US_ASCII));
        assertEquals(0, out[122]);
        assertEquals(new BigDecimal("-10.00"), AccountCodec.decode(out).currBal());
    }

    @Test
    void zeroAmountPostingStillReencodesTouchedFields() {
        byte[] r = original();
        byte[] out = AccountCodec.rewrite(r, AccountCodec.decode(r), false, true);
        assertEquals("000000050000", new String(out, 12, 12, StandardCharsets.US_ASCII));
        assertEquals("000000000000", new String(out, 78, 12, StandardCharsets.US_ASCII));
        assertEquals(new String(r, 90, 12, StandardCharsets.US_ASCII),
                new String(out, 90, 12, StandardCharsets.US_ASCII));
    }
}
