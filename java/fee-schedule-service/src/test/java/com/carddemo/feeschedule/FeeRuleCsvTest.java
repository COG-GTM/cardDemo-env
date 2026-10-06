package com.carddemo.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class FeeRuleCsvTest {

    static List<String> cases() {
        return Fixtures.cases().toList();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void roundTripsFixtureByteForByte(String caseName) throws Exception {
        List<FeeRule> rules = FeeRuleCsv.read(Fixtures.before(caseName));

        assertThat(FeeRuleCsv.write(rules).getBytes(StandardCharsets.US_ASCII))
                .isEqualTo(Files.readAllBytes(Fixtures.expectedAfter(caseName)));
    }

    @Test
    void stripsCharPaddingOnReadAndRestoresItOnWrite() {
        List<FeeRule> rules = FeeRuleCsv.parse("""
                book_id,fee_pct,fee_cap,eff_dt,exp_dt
                RETAIL    ,0.015000,25.00,2024-06-15,9999-12-31
                """);

        assertThat(rules).containsExactly(new FeeRule(
                "RETAIL", new BigDecimal("0.015000"), new BigDecimal("25.00"),
                LocalDate.of(2024, 6, 15), LocalDate.of(9999, 12, 31)));
        assertThat(FeeRuleCsv.write(rules)).contains("RETAIL    ,0.015000,25.00,");
    }

    @Test
    void rejectsUnexpectedHeader() {
        assertThatThrownBy(() -> FeeRuleCsv.parse("book,pct\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
