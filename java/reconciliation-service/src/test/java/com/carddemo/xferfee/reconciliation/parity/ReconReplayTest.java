package com.carddemo.xferfee.reconciliation.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconReplayTest {

    private static final String FEE = "{\"tranId\":\"TRN0000000000002\",\"tranDate\":\"2024-06-20\","
            + "\"sourceAccountId\":1,\"targetAccountId\":2,\"bookId\":\"RETAIL\",\"amount\":\"100.00\","
            + "\"feePct\":\"0.015000\",\"feeAmount\":\"1.50\",\"capApplied\":false,\"ruleEffectiveDate\":\"2024-01-01\"}";

    @TempDir
    Path dir;

    @Test
    void writesReportSysoutAndRcFromContractJsonLines() throws Exception {
        Path fees = Files.writeString(dir.resolve("fees.jsonl"), FEE + "\n");
        Path out = dir.resolve("out");

        ReconReplay.main(new String[] {"--fees", fees.toString(), "--posting-rc", "0", "--out", out.toString()});

        assertThat(Files.readAllLines(out.resolve("XFER.RECON.RPT.txt"))).hasSize(5)
                .contains(" TRN0000000000002 2024-06-20 RETAIL           100.00          1.50");
        assertThat(Files.readAllLines(out.resolve("sysout/STEP030.txt")))
                .containsExactly("CBXFR03C: GRAND TOTAL FEE +00000000150");
        assertThat(Files.readString(out.resolve("rc.json"))).contains("\"STEP030\" : 0");
    }

    @Test
    void bypassWritesNoReportAndNoRc() throws Exception {
        Path fees = Files.writeString(dir.resolve("fees.jsonl"), FEE + "\n");
        Path out = dir.resolve("out");

        ReconReplay.replay(ReconReplay.read(fees), 8, out);

        assertThat(out.resolve("XFER.RECON.RPT.txt")).doesNotExist();
        assertThat(out.resolve("sysout")).doesNotExist();
        assertThat(Files.readString(out.resolve("rc.json"))).doesNotContain("STEP030");
        assertThat(Files.readString(out.resolve("replay.json"))).contains("BYPASSED");
    }
}
