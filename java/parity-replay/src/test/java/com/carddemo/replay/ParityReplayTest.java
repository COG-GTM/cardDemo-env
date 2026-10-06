package com.carddemo.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.observability.XferChainMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ParityReplayTest {

    private static final Path FIXTURES = Path.of("../../fixtures/xferfee");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    static Stream<String> recordedCases() throws IOException {
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            return dirs.filter(d -> Files.exists(d.resolve("case.json")))
                    .map(d -> d.getFileName().toString()).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("recordedCases")
    void countersAndReturnCodesMatchRecordedCobol(String name) throws IOException {
        Path out = work.resolve(name);
        ParityReplay.replayCase(FIXTURES.resolve(name), out);
        Path expected = FIXTURES.resolve(name).resolve("expected");
        for (String step : List.of("STEP010", "STEP020", "STEP030")) {
            assertEquals(Files.readAllLines(expected.resolve("sysout/" + step + ".txt")),
                    Files.readAllLines(out.resolve("sysout/" + step + ".txt")), name + " " + step);
        }
        assertEquals(JSON.readTree(expected.resolve("rc.json").toFile()),
                JSON.readTree(out.resolve("rc.json").toFile()), name);
        assertEquals("", Files.readString(out.resolve("rejects.jsonl")));
        assertEquals("", Files.readString(out.resolve("dlq.jsonl")));
        assertEquals("", Files.readString(out.resolve("alerts.jsonl")));
    }

    @Test
    void unmatchedCardEndsChainWithRc4AndRejectEvent() throws IOException {
        Path fixture = copyDefault();
        Path xref = fixture.resolve("input/CARDXREF.PS");
        byte[] data = Files.readAllBytes(xref);
        Files.write(xref, java.util.Arrays.copyOf(data, FixtureCase.CARDXREF_LRECL));

        XferChainMetrics metrics = ParityReplay.replayCase(fixture, work.resolve("out"));

        JsonNode rc = JSON.readTree(work.resolve("out/rc.json").toFile());
        assertEquals(4, rc.get("maxcc").asInt());
        assertEquals(1, rc.get("steps").size());
        assertTrue(metrics.rejects().size() > 0);
        assertEquals("CARD_NOT_FOUND", metrics.rejects().get(0).reason().name());
        assertEquals("warning", metrics.alerts().get(0).severity());
        String sysout = Files.readString(work.resolve("out/sysout/STEP010.txt"));
        assertTrue(sysout.contains("CBXFR01C: CARD NOT FOUND "), sysout);
        assertTrue(sysout.contains("CBXFR01C: UNMATCHED CARDS 00000000" + metrics.rejects().size()), sysout);
    }

    @Test
    void missingFeeRuleAbendsXferfeeWithRc8AndDeadLetter() throws IOException {
        Path fixture = copyDefault();
        Path rules = fixture.resolve("db2_before/CTL_XFER_PARM.csv");
        List<String> kept = Files.readAllLines(rules).stream().filter(l -> !l.startsWith("RETAIL")).toList();
        Files.write(rules, kept, StandardCharsets.UTF_8);

        XferChainMetrics metrics = ParityReplay.replayCase(fixture, work.resolve("out"));

        JsonNode rc = JSON.readTree(work.resolve("out/rc.json").toFile());
        assertEquals(8, rc.get("steps").get("STEP020").asInt());
        assertEquals(8, rc.get("maxcc").asInt());
        assertEquals("NO_FEE_RULE", metrics.deadLetters().get(0).reason());
        assertEquals("critical", metrics.alerts().get(0).severity());
        List<String> sysout = Files.readAllLines(work.resolve("out/sysout/STEP020.txt"));
        assertEquals("XFERFEE: 9999-ABEND-PROGRAM", sysout.get(sysout.size() - 1));
        assertTrue(sysout.stream().noneMatch(l -> l.contains("TOTAL FEES")));
        assertTrue(Files.readString(work.resolve("out/dlq.jsonl")).contains("\"reason\":\"NO_FEE_RULE\""));
    }

    @Test
    void noTransfersEndsReconWithRc4() throws IOException {
        Path fixture = copyDefault();
        Path tran = fixture.resolve("input/DALYTRAN.PS");
        byte[] data = Files.readAllBytes(tran);
        for (int offset = 0; offset < data.length; offset += FixtureCase.DALYTRAN_LRECL) {
            data[offset + 16] = '0';
            data[offset + 17] = '1';
        }
        Files.write(tran, data);

        XferChainMetrics metrics = ParityReplay.replayCase(fixture, work.resolve("out"));

        JsonNode rc = JSON.readTree(work.resolve("out/rc.json").toFile());
        assertEquals(0, rc.get("steps").get("STEP020").asInt());
        assertEquals(4, rc.get("steps").get("STEP030").asInt());
        assertEquals("CBXFR03C: NO FEE RECORDS\n", Files.readString(work.resolve("out/sysout/STEP030.txt")));
        assertEquals("warning", metrics.alerts().get(0).severity());
    }

    private Path copyDefault() throws IOException {
        Path source = FIXTURES.resolve("default");
        Path target = work.resolve("fixture/default");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }
}
