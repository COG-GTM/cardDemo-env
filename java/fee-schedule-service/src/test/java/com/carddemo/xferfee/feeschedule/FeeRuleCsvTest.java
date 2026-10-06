package com.carddemo.xferfee.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeRule;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeeRuleCsvTest {

    private static final String DEFAULT_SNAPSHOT = """
            book_id,fee_pct,fee_cap,eff_dt,exp_dt
            INSTL     ,0.005000,500.00,2020-01-01,9999-12-31
            RETAIL    ,0.012500,25.00,2020-01-01,2024-06-15
            RETAIL    ,0.015000,25.00,2024-06-15,9999-12-31
            """;

    @Test
    void readsRecorderSnapshot(@TempDir Path dir) throws Exception {
        Path csv = Files.writeString(dir.resolve("CTL_XFER_PARM.csv"), DEFAULT_SNAPSHOT);
        List<FeeRule> rules = FeeRuleCsv.read(csv);
        assertThat(rules).hasSize(3);
        assertThat(rules.get(1)).isEqualTo(new FeeRule("RETAIL", new BigDecimal("0.012500"),
                new BigDecimal("25.00"), LocalDate.parse("2020-01-01"), LocalDate.parse("2024-06-15")));
    }

    @Test
    void roundTripIsByteEqual(@TempDir Path dir) throws Exception {
        Path csv = Files.writeString(dir.resolve("CTL_XFER_PARM.csv"), DEFAULT_SNAPSHOT);
        assertThat(FeeRuleCsv.format(FeeRuleCsv.read(csv))).isEqualTo(DEFAULT_SNAPSHOT);
    }

    @Test
    void writesColumnScaleAndPadding() {
        FeeRule rule = new FeeRule("INSTL", new BigDecimal("0.005"), new BigDecimal("500"),
                LocalDate.parse("2020-01-01"), LocalDate.parse("9999-12-31"));
        assertThat(FeeRuleCsv.format(List.of(rule))).endsWith(
                "\nINSTL     ,0.005000,500.00,2020-01-01,9999-12-31\n");
    }
}
