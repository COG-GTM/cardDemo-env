package com.carddemo.legacy.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.legacy.Estate;
import com.carddemo.legacy.copybook.CopybookParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every fixed-record dataset in the 7 parity fixtures survives decode -> encode byte for byte. */
class FixtureRoundTripTest {
  private static final CopybookParser COPYBOOKS = Estate.copybooks();
  private static final Map<String, String> INPUTS =
      Map.of("ACCTDATA.PS", "CVACT01Y", "CARDXREF.PS", "CVACT03Y", "DALYTRAN.PS", "CVTRA06Y");

  static Stream<Arguments> datasets() throws Exception {
    List<Arguments> args = new ArrayList<>();
    ObjectMapper json = new ObjectMapper();
    for (String c : Estate.CASES) {
      Path fixture = Estate.fixture(c);
      INPUTS.forEach(
          (file, copybook) ->
              args.add(Arguments.of(c + "/input/" + file, fixture.resolve("input").resolve(file), copybook)));
      JsonNode spec = json.readTree(fixture.resolve("case.json").toFile());
      for (JsonNode output : spec.path("outputs")) {
        if (!output.path("text").asBoolean(false)) {
          String dsn = output.path("dsn").asText();
          Path file = fixture.resolve("expected/datasets").resolve(dsn + ".G0001V00");
          args.add(Arguments.of(c + "/expected/" + dsn, file, output.path("copybook").asText()));
        }
      }
    }
    return args.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("datasets")
  void roundTripsByteForByte(String label, Path file, String copybook) throws Exception {
    byte[] original = Files.readAllBytes(file);
    int recordLength = COPYBOOKS.layout(copybook).recordLength();
    assertTrue(original.length > 0 && original.length % recordLength == 0, label);
    for (SignEncoding sign : SignEncoding.values()) {
      CopybookCodec codec = new CopybookCodec(COPYBOOKS.layout(copybook), sign);
      List<CopybookRecord> records = codec.decodeAll(original);
      assertEquals(original.length / recordLength, records.size());
      assertArrayEquals(original, codec.encodeAll(records), label + " via " + sign);
    }
    if (label.contains("/input/")) {
      Set<SignEncoding> used = new CopybookCodec(COPYBOOKS.layout(copybook), SignEncoding.OVERPUNCH).signEncodingsUsed(original);
      assertTrue(used.isEmpty() || used.equals(Set.of(SignEncoding.OVERPUNCH)), label + " uses " + used);
    }
  }
}
