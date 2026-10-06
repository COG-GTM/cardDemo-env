package com.carddemo.parity.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class XferFeeEngineTest {

    static final Path FIXTURES = Path.of("..", "fixtures", "xferfee");

    @ParameterizedTest
    @ValueSource(strings = {"default", "half_cent", "rate_change", "at_cap", "under_cap", "zero_amount", "non_transfer"})
    void ledgerMatchesCobolRecordedLedger(String caseName) throws IOException {
        CaseInputs inputs = CaseInputs.load(FIXTURES, caseName);
        XferFeeEngine engine = inputs.newEngine(FeeRounding.COBOL_ROUNDED);
        List<String> actual = inputs.feed().stream().map(engine::process)
                .map(TransferOutcome::ledger).filter(Objects::nonNull).map(LedgerRow::toCsv).sorted().toList();
        List<String> expected = Files.readAllLines(
                FIXTURES.resolve(caseName).resolve("expected/db2_after/XFER_FEE_LEDGER.csv"));
        assertThat(actual).containsExactlyElementsOf(expected.subList(1, expected.size()));
    }

    @Test
    void breakItHalfEvenDivergesOnHalfCentOnly() {
        CaseInputs halfCent = CaseInputs.load(FIXTURES, "half_cent");
        BigDecimal cobol = totalFees(halfCent, FeeRounding.COBOL_ROUNDED);
        BigDecimal halfEven = totalFees(halfCent, FeeRounding.BREAK_IT_HALF_EVEN);
        assertThat(cobol).isEqualByComparingTo("0.46");
        assertThat(halfEven).isLessThan(cobol);

        CaseInputs atCap = CaseInputs.load(FIXTURES, "at_cap");
        assertThat(totalFees(atCap, FeeRounding.BREAK_IT_HALF_EVEN)).isEqualByComparingTo("525.00");
    }

    @Test
    void rateChangeUsesEffectiveDatedRule() {
        CaseInputs inputs = CaseInputs.load(FIXTURES, "rate_change");
        XferFeeEngine engine = inputs.newEngine(FeeRounding.COBOL_ROUNDED);
        List<String> rules = inputs.feed().stream().map(engine::process)
                .filter(TransferOutcome::selected).map(TransferOutcome::ruleEffDate).toList();
        assertThat(rules).containsExactly("2020-01-01", "2024-06-15", "2024-06-15");
    }

    private static BigDecimal totalFees(CaseInputs inputs, FeeRounding rounding) {
        XferFeeEngine engine = inputs.newEngine(rounding);
        return inputs.feed().stream().map(engine::process).filter(TransferOutcome::selected)
                .map(TransferOutcome::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
