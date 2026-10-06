package com.carddemo.legacy.candidate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.legacy.Estate;
import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookParser;
import com.carddemo.legacy.ingress.JsonLinesSink;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Decoded expected outputs re-encoded by the Java candidate path decode back to identical values. */
class CandidateEncoderTest {
  private final CopybookParser copybooks = Estate.copybooks();

  static List<String> cases() {
    return Estate.CASES;
  }

  @ParameterizedTest
  @MethodSource("cases")
  void candidateMatchesExpectedValues(String caseName, @TempDir Path work) throws Exception {
    Path fixture = Estate.fixture(caseName);
    Path decoded = Files.createDirectories(work.resolve("decoded"));
    JsonNode spec = JsonLinesSink.JSON.readTree(fixture.resolve("case.json").toFile());
    for (JsonNode output : spec.path("outputs")) {
      String dsn = output.path("dsn").asText();
      byte[] bytes = Files.readAllBytes(fixture.resolve("expected/datasets").resolve(dsn + ".G0001V00"));
      List<String> lines = new ArrayList<>();
      if (output.path("text").asBoolean(false)) {
        for (String line : new String(bytes, StandardCharsets.ISO_8859_1).split("\n", -1)) {
          lines.add(JsonLinesSink.JSON.writeValueAsString(Map.of("line", line)));
        }
        lines.remove(lines.size() - 1);
      } else {
        CopybookCodec codec = new CopybookCodec(copybooks.layout(output.path("copybook").asText()), SignEncoding.GNUCOBOL);
        for (CopybookRecord r : codec.decodeAll(bytes)) {
          Map<String, Object> row = new LinkedHashMap<>();
          r.values().forEach((k, v) -> row.put(k, v instanceof BigDecimal d ? d.toPlainString() : v));
          lines.add(JsonLinesSink.JSON.writeValueAsString(row));
        }
      }
      Files.write(decoded.resolve(dsn + ".jsonl"), lines);
    }
    Files.writeString(decoded.resolve("rc.json"), Files.readString(fixture.resolve("expected/rc.json")));

    Path candidate = work.resolve("candidate");
    new CandidateEncoder(copybooks, SignEncoding.OVERPUNCH).encode(fixture.resolve("case.json"), decoded, candidate);

    assertTrue(Files.exists(candidate.resolve("rc.json")));
    for (JsonNode output : spec.path("outputs")) {
      String dsn = output.path("dsn").asText();
      byte[] expected = Files.readAllBytes(fixture.resolve("expected/datasets").resolve(dsn + ".G0001V00"));
      byte[] actual = Files.readAllBytes(candidate.resolve("datasets").resolve(dsn + ".G0001V00"));
      if (output.path("text").asBoolean(false)) {
        assertArrayEquals(expected, actual, dsn);
        continue;
      }
      CopybookCodec codec = new CopybookCodec(copybooks.layout(output.path("copybook").asText()), SignEncoding.OVERPUNCH);
      List<Map<String, Object>> want = codec.decodeAll(expected).stream().map(CopybookRecord::values).toList();
      List<Map<String, Object>> got = codec.decodeAll(actual).stream().map(CopybookRecord::values).toList();
      assertEquals(want, got, dsn);
    }
  }
}
