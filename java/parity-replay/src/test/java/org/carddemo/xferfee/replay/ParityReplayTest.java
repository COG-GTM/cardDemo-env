package org.carddemo.xferfee.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.carddemo.xferfee.contracts.FeeRule;
import org.carddemo.xferfee.fee.CobolFeePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParityReplayTest {

    private static final Path FIXTURES = Path.of(System.getProperty("xferfee.fixtures", "../../fixtures/xferfee"));

    @Test
    void halfCentFeesUseCobolRounding(@TempDir Path out) throws IOException {
        new ParityReplay(new CobolFeePolicy()).run(FIXTURES.resolve("half_cent"), out);
        List<String> lines = Files.readAllLines(out.resolve(ParityReplay.FEES_DSN + ".jsonl"));
        assertEquals(6, lines.size());
        assertTrue(lines.get(0).contains("\"XFE-TRAN-ID\": \"TRN0000000000001\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"XFE-FEE-AMT\": \"0.03\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"XFE-CAP-APPLIED\": \"N\""), lines.get(0));
    }

    @Test
    void nonTransfersAreSkipped() {
        List<ParityReplay.Transfer> transfers =
                ParityReplay.extractTransfers(FIXTURES.resolve("non_transfer/input"));
        assertEquals(List.of("TRN0000000000004"), transfers.stream().map(ParityReplay.Transfer::tranId).toList());
    }

    @Test
    void missingRuleIsAnError() {
        ParityReplay.Transfer transfer = new ParityReplay.Transfer("T", LocalDate.parse("2024-06-05"), 1, 2,
                "UNKNOWN", BigDecimal.ONE);
        List<FeeRule> rules = ParityReplay.loadRules(FIXTURES.resolve("half_cent/db2_before/CTL_XFER_PARM.csv"));
        assertThrows(ParityReplay.NoFeeRuleException.class, () -> ParityReplay.resolveRule(rules, transfer));
    }
}
