package com.carddemo.xfer.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolTest {

    @Test
    void zonedOverpunchRoundTrips() {
        byte[] r = new byte[11];
        Cobol.putZoned(r, 0, 11, 2, new BigDecimal("100.00"));
        assertEquals("0000001000{", Cobol.text(r, 0, 11));
        Cobol.putZoned(r, 0, 11, 2, new BigDecimal("-12.34"));
        assertEquals(new BigDecimal("-12.34"), Cobol.zoned(r, 0, 11, 2));
    }

    @Test
    void packedMatchesComp3() {
        byte[] r = new byte[6];
        Cobol.putPacked(r, 0, 6, 2, new BigDecimal("100.00"));
        assertArrayEquals(new byte[] {0, 0, 0, 0x10, 0x00, 0x0C}, r);
        assertEquals(new BigDecimal("100.00"), Cobol.packed(r, 0, 6, 2));
        byte[] pct = new byte[4];
        Cobol.putPacked(pct, 0, 4, 6, new BigDecimal("0.015000"));
        assertArrayEquals(new byte[] {0x00, 0x15, 0x00, 0x0C}, pct);
    }

    @Test
    void editedPictures() {
        assertEquals("       100.00 ", " " + Cobol.editAmount(new BigDecimal("100.00")));
        assertEquals("        0.00 ", Cobol.editAmount(BigDecimal.ZERO));
        assertEquals("        2.50-", Cobol.editAmount(new BigDecimal("-2.5")));
        assertEquals("+00000000650", Cobol.displaySigned(new BigDecimal("6.50"), 11, 2));
    }
}
