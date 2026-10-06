package com.carddemo.xferfee.legacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class Fixtures {

    static final Path ROOT = Path.of(System.getProperty("carddemo.fixtures", "../../fixtures"));
    static final Path XFERFEE = ROOT.resolve("xferfee");

    private Fixtures() {
    }

    static List<Path> cases() {
        try (Stream<Path> dirs = Files.list(XFERFEE)) {
            List<Path> cases = dirs.filter(dir -> Files.exists(dir.resolve("case.json"))).sorted().toList();
            if (cases.size() < 7) {
                throw new IllegalStateException("expected the 7 xferfee fixture cases under " + XFERFEE);
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> files(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
