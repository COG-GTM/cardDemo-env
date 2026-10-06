package com.carddemo.xferfee.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.port.ReconciliationReport;
import com.carddemo.xferfee.reconciliation.parity.Cvxfr02yReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Replays each recorded case's XFER.FEES through STEP030 and compares with the COBOL recording. */
class FixtureParityTest {

    private static final Path ROOT = Path.of(System.getProperty("carddemo.root", "../.."));
    private static final String RPT = "AWS.M2.CARDDEMO.XFER.RECON.RPT.G0001V00";
    private static final String FEES = "AWS.M2.CARDDEMO.XFER.FEES.G0001V00";

    @ParameterizedTest
    @ValueSource(strings = {"default", "under_cap", "at_cap", "rate_change", "zero_amount", "non_transfer", "half_cent"})
    void matchesCobolRecording(String caseName) throws IOException {
        Path expected = ROOT.resolve("fixtures/xferfee").resolve(caseName).resolve("expected");
        String rc = Files.readString(expected.resolve("rc.json"));

        ReconciliationReport report = new LegacyReconStep()
                .run(Cvxfr02yReader.read(expected.resolve("datasets").resolve(FEES)), stepRc(rc, "STEP020"))
                .orElseThrow();

        assertThat(report.lines()).containsExactlyElementsOf(lines(expected.resolve("datasets").resolve(RPT)));
        assertThat(report.report().sysout()).containsExactlyElementsOf(lines(expected.resolve("sysout/STEP030.txt")));
        assertThat(report.report().returnCode()).isEqualTo(stepRc(rc, "STEP030"));
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readString(file).lines().toList();
    }

    private static int stepRc(String rcJson, String step) {
        Matcher m = Pattern.compile("\"" + step + "\"\\s*:\\s*(\\d+)").matcher(rcJson);
        assertThat(m.find()).as("%s in rc.json", step).isTrue();
        return Integer.parseInt(m.group(1));
    }
}
