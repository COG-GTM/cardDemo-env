package com.carddemo.xferfee.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolEditTest {

    @Test
    void amountEditsLikeZ8NinePointNineNineMinus() {
        assertThat(CobolEdit.amount(new BigDecimal("100.00"))).isEqualTo("      100.00 ");
        assertThat(CobolEdit.amount(new BigDecimal("0"))).isEqualTo("        0.00 ");
        assertThat(CobolEdit.amount(new BigDecimal("-1.5"))).isEqualTo("        1.50-");
        assertThat(CobolEdit.amount(new BigDecimal("1234567890.129"))).isEqualTo("234567890.12 ");
    }

    @Test
    void countAndDisplay() {
        assertThat(CobolEdit.count(2)).isEqualTo("        2");
        assertThat(CobolEdit.displaySigned(new BigDecimal("6.50"))).isEqualTo("+00000000650");
        assertThat(CobolEdit.displaySigned(new BigDecimal("-0.03"))).isEqualTo("-00000000003");
    }

    @Test
    void accumulatorsDropHighOrderDigits() {
        assertThat(CobolEdit.fit(new BigDecimal("1000000000.01"), 9, 2)).isEqualByComparingTo("0.01");
    }
}
