package com.carddemo.legacy.candidate;

import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookLayout;
import com.carddemo.legacy.copybook.CopybookParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Encodes a parity candidate directory from decoded records ({@code --codec=java}).
 *
 * <p>Input: one {@code <DSN>.jsonl} per output listed in {@code case.json}; record datasets hold one
 * JSON object of copybook field values per line, text datasets hold {@code {"line": "..."}}. Output:
 * {@code datasets/<DSN>.G0001V00}, the layout {@code tools/parity/compare.py} reads. {@code
 * db2_after/}, {@code sysout/} and {@code rc.json} are copied through when present.
 */
public final class CandidateEncoder {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private final CopybookParser copybooks;
  private final SignEncoding signEncoding;

  public CandidateEncoder(CopybookParser copybooks, SignEncoding signEncoding) {
    this.copybooks = copybooks;
    this.signEncoding = signEncoding;
  }

  public List<Path> encode(Path caseJson, Path decodedDirectory, Path candidateDirectory)
      throws IOException {
    JsonNode spec = JSON.readTree(caseJson.toFile());
    Path datasets = Files.createDirectories(candidateDirectory.resolve("datasets"));
    List<Path> written = new ArrayList<>();
    for (JsonNode output : spec.path("outputs")) {
      String dsn = output.path("dsn").asText();
      Path source = decodedDirectory.resolve(dsn + ".jsonl");
      if (!Files.exists(source)) {
        throw new IOException("missing decoded dataset " + source);
      }
      List<JsonNode> rows = readJsonLines(source);
      byte[] content =
          output.path("text").asBoolean(false)
              ? encodeText(rows)
              : encodeRecords(copybooks.layout(output.path("copybook").asText()), rows);
      Path target = datasets.resolve(dsn + ".G0001V00");
      Files.write(target, content);
      written.add(target);
    }
    copyIfPresent(decodedDirectory.resolve("db2_after"), candidateDirectory.resolve("db2_after"));
    copyIfPresent(decodedDirectory.resolve("sysout"), candidateDirectory.resolve("sysout"));
    copyIfPresent(decodedDirectory.resolve("rc.json"), candidateDirectory.resolve("rc.json"));
    return written;
  }

  private byte[] encodeRecords(CopybookLayout layout, List<JsonNode> rows) {
    CopybookCodec codec = new CopybookCodec(layout, signEncoding);
    List<CopybookRecord> records = new ArrayList<>(rows.size());
    for (JsonNode row : rows) {
      CopybookRecord.Builder builder = CopybookRecord.builder(layout);
      Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        builder.set(field.getKey(), value.isNumber() ? value.decimalValue() : value.asText());
      }
      records.add(builder.build());
    }
    return codec.encodeAll(records);
  }

  private static byte[] encodeText(List<JsonNode> rows) {
    StringBuilder text = new StringBuilder();
    for (JsonNode row : rows) {
      text.append(row.path("line").asText()).append('\n');
    }
    return text.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static List<JsonNode> readJsonLines(Path file) throws IOException {
    List<JsonNode> rows = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        rows.add(JSON.readTree(line));
      }
    }
    return rows;
  }

  private static void copyIfPresent(Path source, Path target) throws IOException {
    if (!Files.exists(source)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(source)) {
      walk.forEach(
          path -> {
            Path destination = target.resolve(source.relativize(path).toString());
            try {
              if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
              } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
  }
}
