package com.carddemo.xferfee.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Renders each COBOL-recorded XFER.FEES and compares with the recorded STEP030 outputs. */
class FixtureParityTest {

    static final Path CASES = Path.of("..", "..", "fixtures", "xferfee");

    @ParameterizedTest
    @ValueSource(strings = {"default", "at_cap", "under_cap", "half_cent", "rate_change", "zero_amount",
            "non_transfer"})
    void matchesRecordedReport(String name) throws IOException {
        Path expected = CASES.resolve(name).resolve("expected");
        Path fees = expected.resolve("datasets").resolve("AWS.M2.CARDDEMO.XFER.FEES.G0001V00");

        LegacyReconReport report = new LegacyReconReportRenderer()
                .render(new XferFeesDatasetReader().read(fees, LocalDate.of(2024, 6, 30)));

        assertThat(report.text()).isEqualTo(read(expected.resolve("datasets")
                .resolve("AWS.M2.CARDDEMO.XFER.RECON.RPT.G0001V00")));
        assertThat(report.sysoutText()).isEqualTo(read(expected.resolve("sysout").resolve("STEP030.txt")));
        assertThat(Files.readString(expected.resolve("rc.json")))
                .contains("\"STEP030\": " + report.returnCode());
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.US_ASCII);
    }
}
