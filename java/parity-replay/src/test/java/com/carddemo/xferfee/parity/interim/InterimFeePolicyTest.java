package com.carddemo.xferfee.parity.interim;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterimFeePolicyTest {

    private static final FeeRule RETAIL = new FeeRule("RETAIL", new BigDecimal("0.012500"), new BigDecimal("25.00"),
            LocalDate.of(2020, 1, 1), LocalDate.of(2024, 6, 15));

    private final InterimFeePolicy policy = new InterimFeePolicy();

    @Test
    void roundsHalfUpThenCapsWhenStrictlyGreater() {
        assertThat(policy.apply(new BigDecimal("0.20"), RETAIL)).isEqualTo(new FeeResult(new BigDecimal("0.00"), false));
        assertThat(policy.apply(new BigDecimal("0.40"), RETAIL)).isEqualTo(new FeeResult(new BigDecimal("0.01"), false));
        assertThat(policy.apply(new BigDecimal("2000.00"), RETAIL)).isEqualTo(new FeeResult(new BigDecimal("25.00"), false));
        assertThat(policy.apply(new BigDecimal("2000.39"), RETAIL)).isEqualTo(new FeeResult(new BigDecimal("25.00"), false));
        assertThat(policy.apply(new BigDecimal("2000.40"), RETAIL)).isEqualTo(new FeeResult(new BigDecimal("25.00"), true));
        assertThat(policy.apply(BigDecimal.ZERO, RETAIL)).isEqualTo(new FeeResult(new BigDecimal("0.00"), false));
    }

    @Test
    void scheduleWindowIsHalfOpen() {
        InterimFeeSchedule schedule = new InterimFeeSchedule();
        schedule.seed(List.of(RETAIL));
        assertThat(schedule.effectiveRule("RETAIL    ", LocalDate.of(2024, 6, 14))).contains(RETAIL);
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.of(2024, 6, 15))).isEmpty();
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.of(2019, 12, 31))).isEmpty();
    }

    @Test
    void targetAccountComesFromDescriptionColumns14To24() {
        assertThat(InterimTransferIntake.targetAccount("XFER TO ACCT 00000000002")).isEqualTo(2L);
        assertThat(InterimTransferIntake.targetAccount("short")).isZero();
    }
}
