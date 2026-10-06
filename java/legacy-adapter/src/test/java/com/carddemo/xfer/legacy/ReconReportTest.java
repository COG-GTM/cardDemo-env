package com.carddemo.xfer.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconReportTest {

    @Test
    void rendersDefaultFixtureLayout() {
        ReconReport.Result result = ReconReport.render(List.of(
                new ReconReport.Entry("TRN0000000000002", "2024-06-20", "RETAIL",
                        new BigDecimal("100.00"), new BigDecimal("1.50")),
                new ReconReport.Entry("TRN0000000000003", "2024-06-21", "INSTL",
                        new BigDecimal("1000.00"), new BigDecimal("5.00"))));
        assertEquals(List.of(
                " TRANSFER FEE RECONCILIATION",
                " TRANSACTION       DATE       BOOK       AMOUNT          FEE",
                " TRN0000000000002 2024-06-20 RETAIL           100.00          1.50",
                " BOOK RETAIL     SUBTOTAL AMOUNT       100.00  FEE         1.50",
                " TRN0000000000003 2024-06-21 INSTL           1000.00          5.00",
                " BOOK INSTL      SUBTOTAL AMOUNT      1000.00  FEE         5.00",
                " GRAND TOTAL COUNT         2 AMOUNT      1100.00  FEE         6.50"),
                result.lines());
        assertEquals(List.of("CBXFR03C: GRAND TOTAL FEE +00000000650"), result.sysout());
        assertEquals(0, result.rc());
    }

    @Test
    void emptyRunWarns() {
        ReconReport.Result result = ReconReport.render(List.of());
        assertEquals(2, result.lines().size());
        assertEquals(4, result.rc());
    }
}
