package com.carddemo.xferfee.shadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Replays every recorded fixture and checks the candidate is byte-identical to the COBOL-recorded outputs. */
class FixtureParityTest {

    static final Path FIXTURES = Path.of(System.getProperty("xferfee.fixtures", "../../fixtures/xferfee"));

    @TempDir
    Path out;

    static Stream<String> cases() throws IOException {
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            return dirs.filter(dir -> Files.exists(dir.resolve("expected/rc.json")))
                    .map(dir -> dir.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void candidateMatchesRecordedCobolOutputs(String name) throws IOException {
        Path caseDir = FIXTURES.resolve(name);
        ShadowRunApplication.replay(caseDir.resolve("input"), caseDir.resolve("db2_before/CTL_XFER_PARM.csv"),
                caseDir.resolve("db2_before/XFER_FEE_LEDGER.csv"), out);
        Path expected = caseDir.resolve("expected");
        try (Stream<Path> files = Files.walk(expected)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path actual = out.resolve(expected.relativize(file).toString());
                assertThat(actual).as(name + ": " + expected.relativize(file)).exists();
                if (file.toString().endsWith(".csv")) {
                    assertThat(Files.readAllLines(actual)).as(name + ": " + file.getFileName())
                            .containsExactlyInAnyOrderElementsOf(Files.readAllLines(file));
                } else {
                    assertThat(Files.readAllBytes(actual)).as(name + ": " + expected.relativize(file))
                            .isEqualTo(Files.readAllBytes(file));
                }
            }
        }
    }
}
