package com.carddemo.xferfee.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class InMemoryFeeScheduleTest {

    private final InMemoryFeeSchedule schedule = new InMemoryFeeSchedule();

    @BeforeEach
    void seedDefaultFixture() throws Exception {
        FeeRuleSnapshots.seed(schedule, FeeScheduleIntegrationTest.FIXTURES.resolve("default/db2_before"));
    }

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource({
            "RETAIL, 2024-06-14, 0.012500, 2020-01-01",
            "RETAIL, 2024-06-15, 0.015000, 2024-06-15",
            "RETAIL, 2024-06-16, 0.015000, 2024-06-15",
            "INSTL,  2024-06-15, 0.005000, 2020-01-01",
            "'RETAIL    ', 2024-06-15, 0.015000, 2024-06-15",
    })
    void halfOpenWindowOnTransactionDate(String book, String date, String pct, String eff) {
        FeeRule rule = schedule.effectiveRule(book, LocalDate.parse(date)).orElseThrow();
        assertThat(rule.feePct()).isEqualByComparingTo(pct);
        assertThat(rule.effectiveDate()).isEqualTo(LocalDate.parse(eff));
    }

    @ParameterizedTest
    @CsvSource({"RETAIL, 2019-12-31", "RETAIL, 9999-12-31", "UNKNOWN, 2024-06-15"})
    void noRuleIsEmpty(String book, String date) {
        assertThat(schedule.effectiveRule(book, LocalDate.parse(date))).isEmpty();
    }

    @Test
    void overlappingLegacyRowsAreAmbiguous() {
        schedule.seed(List.of(
                rule("RETAIL", "0.012500", "2020-01-01", "9999-12-31"),
                rule("RETAIL", "0.015000", "2024-06-15", "9999-12-31")));
        assertThat(schedule.effectiveRule("RETAIL", LocalDate.parse("2024-06-14"))).isPresent();
        assertThatThrownBy(() -> schedule.effectiveRule("RETAIL", LocalDate.parse("2024-06-15")))
                .isInstanceOf(AmbiguousFeeRuleException.class);
    }

    @Test
    void invalidBookIsRejected() {
        assertThatThrownBy(() -> schedule.effectiveRule(" ", LocalDate.parse("2024-06-15")))
                .isInstanceOf(InvalidFeeRuleException.class);
        assertThatThrownBy(() -> schedule.effectiveRule("ELEVENCHARS", LocalDate.parse("2024-06-15")))
                .isInstanceOf(InvalidFeeRuleException.class);
    }

    @Test
    void seedSortsInTableOrderAndTrimsBooks() {
        schedule.seed(List.of(
                rule("RETAIL    ", "0.015000", "2024-06-15", "9999-12-31"),
                rule("INSTL", "0.005000", "2020-01-01", "9999-12-31"),
                rule("RETAIL", "0.012500", "2020-01-01", "2024-06-15")));
        assertThat(schedule.rules()).extracting(FeeRule::bookId).containsExactly("INSTL", "RETAIL", "RETAIL");
        assertThat(schedule.rules()).extracting(FeeRule::effectiveDate).containsExactly(
                LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-01"), LocalDate.parse("2024-06-15"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void replayLeavesDb2AfterByteEqual(String fixtureCase, @TempDir Path out) throws Exception {
        Path caseDir = FeeScheduleIntegrationTest.FIXTURES.resolve(fixtureCase);
        FeeSchedule fresh = new InMemoryFeeSchedule();
        FeeRuleSnapshots.seed(fresh, caseDir.resolve("db2_before"));
        Path dumped = FeeRuleSnapshots.dump(fresh, out);
        assertThat(Files.mismatch(dumped,
                caseDir.resolve("expected/db2_after/" + FeeRuleSnapshots.TABLE_FILE))).isEqualTo(-1L);
    }

    static Stream<String> fixtureCases() throws Exception {
        return FeeScheduleIntegrationTest.fixtureCases();
    }

    @Test
    void windowHelpers() {
        FeeRule old = rule("CORP", "0.01", "2020-01-01", "2024-06-15");
        FeeRule adjacent = rule("CORP", "0.02", "2024-06-15", "9999-12-31");
        FeeRule inside = rule("CORP  ", "0.02", "2024-01-01", "2024-02-01");
        FeeRule otherBook = rule("RETAIL", "0.02", "2024-01-01", "2024-02-01");
        assertThat(FeeRules.overlaps(old, adjacent)).isFalse();
        assertThat(FeeRules.overlaps(old, inside)).isTrue();
        assertThat(FeeRules.overlaps(old, otherBook)).isFalse();
        assertThat(FeeRules.isEffectiveOn(old, LocalDate.parse("2024-06-14"))).isTrue();
        assertThat(FeeRules.isEffectiveOn(old, LocalDate.parse("2024-06-15"))).isFalse();
    }

    private static FeeRule rule(String book, String pct, String eff, String exp) {
        return new FeeRule(book, new BigDecimal(pct), new BigDecimal("25.00"),
                LocalDate.parse(eff), LocalDate.parse(exp));
    }
}
