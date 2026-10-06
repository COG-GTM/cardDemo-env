package com.carddemo.xferfee.reconciliation.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolEditTest {

    @Test
    void editAmountZeroSuppressesAndTrailsSign() {
        assertThat(CobolEdit.editAmount(new BigDecimal("100.00"))).isEqualTo("      100.00 ");
        assertThat(CobolEdit.editAmount(new BigDecimal("0"))).isEqualTo("        0.00 ");
        assertThat(CobolEdit.editAmount(new BigDecimal("0.05"))).isEqualTo("        0.05 ");
        assertThat(CobolEdit.editAmount(new BigDecimal("-12.5"))).isEqualTo("       12.50-");
        assertThat(CobolEdit.editAmount(new BigDecimal("999999999.99"))).isEqualTo("999999999.99 ");
    }

    @Test
    void s9v99TruncatesHighOrderDigitsLikeAddWithoutSizeError() {
        assertThat(CobolEdit.fitS9v99(new BigDecimal("1000000001.25"))).isEqualByComparingTo("1.25");
        assertThat(CobolEdit.fitS9v99(new BigDecimal("-1000000001.25"))).isEqualByComparingTo("-1.25");
        assertThat(CobolEdit.fitS9v99(new BigDecimal("1.239"))).isEqualByComparingTo("1.23");
    }

    @Test
    void editCountAndDisplay() {
        assertThat(CobolEdit.editCount(2)).isEqualTo("        2");
        assertThat(CobolEdit.editCount(0)).isEqualTo("        0");
        assertThat(CobolEdit.displaySigned(new BigDecimal("6.50"))).isEqualTo("+00000000650");
        assertThat(CobolEdit.displaySigned(new BigDecimal("-1.25"))).isEqualTo("-00000000125");
        assertThat(CobolEdit.displaySigned(BigDecimal.ZERO)).isEqualTo("+00000000000");
    }

    @Test
    void alnumPadsAndTruncates() {
        assertThat(CobolEdit.alnum("RETAIL", 10)).isEqualTo("RETAIL    ");
        assertThat(CobolEdit.alnum("ABCDEFGHIJKL", 10)).isEqualTo("ABCDEFGHIJ");
        assertThat(CobolEdit.alnum(null, 3)).isEqualTo("   ");
    }
}
