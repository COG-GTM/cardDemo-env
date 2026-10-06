package com.carddemo.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SysoutFormatTest {

    @Test
    void countsRenderAsNineDigitUnsigned() {
        assertEquals("CBXFR01C: RECORDS READ 000000004",
                SysoutFormat.line(LegacyCounter.RECORDS_READ, BigDecimal.valueOf(4)));
    }

    @Test
    void amountsRenderWithLeadingSignAndElevenDigits() {
        assertEquals("XFERFEE: TOTAL FEES +00000000650",
                SysoutFormat.line(LegacyCounter.TOTAL_FEES, new BigDecimal("6.50")));
        assertEquals("-00000000125", SysoutFormat.value(LegacyCounter.GRAND_TOTAL_FEE, new BigDecimal("-1.25")));
        assertEquals("+00000000000", SysoutFormat.value(LegacyCounter.GRAND_TOTAL_FEE, BigDecimal.ZERO));
    }

    @Test
    void countersTruncateHighOrderDigitsLikeThePicClause() {
        StepCounters counters = new StepCounters(ChainStep.STEP020);
        counters.add(LegacyCounter.TOTAL_FEES, new BigDecimal("999999999.99"));
        counters.add(LegacyCounter.TOTAL_FEES, new BigDecimal("0.02"));
        assertEquals(new BigDecimal("0.01"), counters.get(LegacyCounter.TOTAL_FEES));
    }
}
