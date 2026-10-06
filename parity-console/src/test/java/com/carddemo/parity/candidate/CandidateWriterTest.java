package com.carddemo.parity.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.parity.engine.CaseInputs;
import com.carddemo.parity.engine.FeeRounding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Byte-level check of the non-filler outputs; tools/parity/compare.py is the authoritative gate. */
class CandidateWriterTest {

    static final Path FIXTURES = Path.of("..", "fixtures", "xferfee");

    @ParameterizedTest
    @ValueSource(strings = {"default", "half_cent", "rate_change", "at_cap", "under_cap", "zero_amount", "non_transfer"})
    void textOutputsMatchCobolRecording(String caseName, @TempDir Path out) throws IOException {
        CandidateWriter.write(CaseInputs.load(FIXTURES, caseName), FeeRounding.COBOL_ROUNDED, out);
        Path expected = FIXTURES.resolve(caseName).resolve("expected");
        for (String step : new String[] {"STEP010.txt", "STEP020.txt", "STEP030.txt"}) {
            assertThat(out.resolve("sysout").resolve(step)).hasSameTextualContentAs(expected.resolve("sysout").resolve(step));
        }
        String recon = "datasets/AWS.M2.CARDDEMO.XFER.RECON.RPT.G0001V00";
        assertThat(out.resolve(recon)).hasSameTextualContentAs(expected.resolve(recon));
        assertThat(Files.readString(out.resolve("rc.json")).replaceAll("\\s", ""))
                .isEqualTo(Files.readString(expected.resolve("rc.json")).replaceAll("\\s", ""));
        for (String dsn : new String[] {"XFER.EXTRACT", "XFER.FEES", "ACCTDATA.XFER"}) {
            String name = "datasets/AWS.M2.CARDDEMO." + dsn + ".G0001V00";
            assertThat(Files.readAllBytes(out.resolve(name))).as(dsn).isEqualTo(Files.readAllBytes(expected.resolve(name)));
        }
    }
}
