package com.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LegacyDisplayTest {

    @Test
    void formatsSignedDisplayLikeCobol() {
        assertThat(LegacyDisplay.signed(new BigDecimal("6.50"), 11, 2)).isEqualTo("+00000000650");
        assertThat(LegacyDisplay.signed(new BigDecimal("0.00"), 11, 2)).isEqualTo("+00000000000");
        assertThat(LegacyDisplay.signed(new BigDecimal("-1.5"), 11, 2)).isEqualTo("-00000000150");
    }
}
