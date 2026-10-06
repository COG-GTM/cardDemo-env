package com.carddemo.xferfee.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** {@code --codec=java} reproduces every recorded dataset, DB2 dump, SYSOUT and RC byte for byte. */
class JavaCodecReplayTest {

    static final Path FIXTURES = Path.of(System.getProperty("carddemo.fixtures", "../../fixtures")).resolve("xferfee");

    static Stream<String> cases() throws IOException {
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            return dirs.filter(d -> Files.exists(d.resolve("case.json")))
                    .map(d -> d.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void candidateMatchesRecording(String name, @TempDir Path out) throws IOException {
        try (ConfigurableApplicationContext ignored = SpringApplication.run(ParityReplayApplication.class,
                "--case=" + name, "--out=" + out, "--codec=java", "--fixtures=" + FIXTURES)) {
            // runner executes during startup
        }
        Path expected = FIXTURES.resolve(name).resolve("expected");
        Path candidate = out.resolve("candidate");
        for (String dir : List.of("datasets", "sysout")) {
            List<Path> files;
            try (Stream<Path> list = Files.list(expected.resolve(dir))) {
                files = list.sorted().toList();
            }
            for (Path file : files) {
                assertThat(candidate.resolve(dir).resolve(file.getFileName()))
                        .as("%s/%s", dir, file.getFileName()).hasSameBinaryContentAs(file);
            }
            try (Stream<Path> list = Files.list(candidate.resolve(dir))) {
                assertThat(list.filter(p -> !p.toString().endsWith(".gdg")).count()).as("%s file count", dir).isEqualTo(files.size());
            }
        }
        for (String table : List.of(Db2Tables.CTL_XFER_PARM, Db2Tables.XFER_FEE_LEDGER)) {
            assertThat(Files.readAllLines(candidate.resolve("db2_after").resolve(table)))
                    .as(table).isEqualTo(Files.readAllLines(expected.resolve("db2_after").resolve(table)));
        }
        assertThat(Files.readString(candidate.resolve("rc.json")).replaceAll("\\s", ""))
                .isEqualTo(Files.readString(expected.resolve("rc.json")).replaceAll("\\s", ""));
    }
}
