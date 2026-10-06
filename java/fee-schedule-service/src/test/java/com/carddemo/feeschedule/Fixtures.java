package com.carddemo.feeschedule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

final class Fixtures {

    static final Path XFERFEE = Path.of("..", "..", "fixtures", "xferfee").toAbsolutePath().normalize();

    private Fixtures() {
    }

    static Stream<String> cases() {
        try (Stream<Path> dirs = Files.list(XFERFEE)) {
            return dirs.filter(dir -> Files.exists(dir.resolve("case.json")))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path before(String caseName) {
        return XFERFEE.resolve(caseName).resolve("db2_before").resolve("CTL_XFER_PARM.csv");
    }

    static Path expectedAfter(String caseName) {
        return XFERFEE.resolve(caseName).resolve("expected").resolve("db2_after").resolve("CTL_XFER_PARM.csv");
    }
}
