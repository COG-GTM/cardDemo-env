package com.carddemo.legacy;

import com.carddemo.legacy.copybook.CopybookParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Locates the estate checkout (copybooks and parity fixtures) for tests. */
public final class Estate {
  public static final List<String> CASES =
      List.of("default", "under_cap", "at_cap", "rate_change", "zero_amount", "non_transfer", "half_cent");

  private Estate() {}

  public static List<String> cases() {
    return CASES;
  }

  public static Path root() {
    String configured = System.getProperty("estate.root");
    if (configured != null && Files.isDirectory(Path.of(configured, "copybook"))) {
      return Path.of(configured).toAbsolutePath().normalize();
    }
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("copybook")) && Files.isDirectory(dir.resolve("fixtures"))) {
        return dir;
      }
    }
    throw new IllegalStateException("estate root not found; set -Destate.root");
  }

  public static CopybookParser copybooks() {
    return new CopybookParser(root().resolve("copybook"));
  }

  public static Path fixture(String caseName) {
    return root().resolve("fixtures").resolve("xferfee").resolve(caseName);
  }

  public static List<Path> files(Path dir) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
