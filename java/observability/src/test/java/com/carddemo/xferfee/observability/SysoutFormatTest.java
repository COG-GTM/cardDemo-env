package com.carddemo.xferfee.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SysoutFormatTest {

    @Test
    void countIsPic9Of9() {
        assertThat(SysoutFormat.count(4)).isEqualTo("000000004");
        assertThat(SysoutFormat.count(1_234_567_890L)).isEqualTo("234567890");
    }

    @Test
    void moneyIsSignedPicS9Of9V99() {
        assertThat(SysoutFormat.signedMoney(new BigDecimal("6.50"))).isEqualTo("+00000000650");
        assertThat(SysoutFormat.signedMoney(new BigDecimal("0.46"))).isEqualTo("+00000000046");
        assertThat(SysoutFormat.signedMoney(BigDecimal.ZERO)).isEqualTo("+00000000000");
        assertThat(SysoutFormat.signedMoney(new BigDecimal("-1.005"))).isEqualTo("-00000000100");
    }

    @Test
    void textAndAccountIdArePadded() {
        assertThat(SysoutFormat.text("RETAIL", 10)).isEqualTo("RETAIL    ");
        assertThat(SysoutFormat.text("ABCDEFGHIJKL", 10)).isEqualTo("ABCDEFGHIJ");
        assertThat(SysoutFormat.accountId(7)).isEqualTo("00000000007");
    }

    @Test
    void returnCodePolicyMapsRc4ToWarningAndRc8ToAlert() {
        assertThat(ReturnCodePolicy.classify(0)).isEqualTo(Severity.OK);
        assertThat(ReturnCodePolicy.classify(4)).isEqualTo(Severity.WARNING);
        assertThat(ReturnCodePolicy.classify(8)).isEqualTo(Severity.ALERT);
        assertThat(ReturnCodePolicy.classify(12)).isEqualTo(Severity.ALERT);
    }
}
