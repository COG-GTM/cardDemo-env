package com.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.contracts.FeeRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvFeeScheduleTest {

    private final CsvFeeSchedule schedule = new CsvFeeSchedule();

    @Test
    void appliesEffectiveDatesWithExclusiveExpiry() {
        schedule.load(Path.of("../../fixtures/xferfee/rate_change/db2_before/CTL_XFER_PARM.csv"));

        assertThat(schedule.ruleFor("RETAIL", LocalDate.parse("2024-06-14")))
                .map(FeeRule::feePct).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0.0125"));
        assertThat(schedule.ruleFor("RETAIL    ", LocalDate.parse("2024-06-15")))
                .map(FeeRule::feePct).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("0.015"));
        assertThat(schedule.ruleFor("CORP", LocalDate.parse("2024-06-15"))).isEmpty();
    }

    @Test
    void overlappingRulesFailLikeSqlcode811(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("CTL_XFER_PARM.csv");
        Files.writeString(csv, """
                book_id,fee_pct,fee_cap,eff_dt,exp_dt
                RETAIL    ,0.012500,25.00,2020-01-01,9999-12-31
                RETAIL    ,0.015000,25.00,2024-06-15,9999-12-31
                """);
        schedule.load(csv);

        assertThatThrownBy(() -> schedule.ruleFor("RETAIL", LocalDate.parse("2024-06-20")))
                .isInstanceOf(IllegalStateException.class).hasMessage("-811");
    }
}
