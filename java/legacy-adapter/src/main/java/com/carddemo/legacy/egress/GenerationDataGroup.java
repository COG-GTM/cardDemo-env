package com.carddemo.legacy.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * File-system GDG matching the estate's runjcl convention: generations are {@code BASE.GnnnnV00}
 * beside a {@code BASE.gdg} catalog holding {@code {"current": n}}. Generations older than the
 * {@code LIMIT} are scratched, mirroring {@code DEFINE GENERATIONDATAGROUP ... SCRATCH}.
 */
public final class GenerationDataGroup {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path directory;
  private final String baseName;
  private final int limit;

  public GenerationDataGroup(Path directory, String baseName, int limit) {
    if (limit < 1 || limit > 255) {
      throw new IllegalArgumentException("GDG limit must be 1..255: " + limit);
    }
    this.directory = directory;
    this.baseName = baseName;
    this.limit = limit;
  }

  public Path catalog() {
    return directory.resolve(baseName + ".gdg");
  }

  public int current() throws IOException {
    Path catalog = catalog();
    if (!Files.exists(catalog)) {
      return 0;
    }
    JsonNode node = JSON.readTree(Files.readString(catalog, StandardCharsets.UTF_8));
    return node.path("current").asInt(0);
  }

  public Path generation(int number) {
    return directory.resolve(String.format("%s.G%04dV00", baseName, number));
  }

  /**
   * Writes {@code content} as generation (+1): the data is staged and moved into place before the
   * catalog is advanced, so readers of (0) never observe a partial generation.
   */
  public Path writeNewGeneration(byte[] content) throws IOException {
    Files.createDirectories(directory);
    int next = current() + 1;
    if (next > 9999) {
      throw new IOException(baseName + ": generation number overflow");
    }
    Path target = generation(next);
    Path staged = Files.createTempFile(directory, baseName + ".", ".tmp");
    try {
      Files.write(staged, content);
      Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(staged);
    }
    Path catalogTmp = Files.createTempFile(directory, baseName + ".gdg.", ".tmp");
    Files.writeString(catalogTmp, JSON.writeValueAsString(Map.of("current", next)) + "\n");
    Files.move(catalogTmp, catalog(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    for (int old = next - limit; old >= 1; old--) {
      if (!Files.deleteIfExists(generation(old))) {
        break;
      }
    }
    return target;
  }
}
