package com.carddemo.xferfee.parity;

import java.nio.file.Files;
import java.nio.file.Path;

record ReplayArgs(String caseName, Path out, Path fixtures, Path lookups) {

    static ReplayArgs parse(String... args) {
        String caseName = null;
        Path out = null;
        Path fixtures = null;
        Path lookups = null;
        for (int i = 0; i < args.length; i++) {
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--case" -> caseName = require(args[i], value);
                case "--out" -> out = Path.of(require(args[i], value));
                case "--fixtures" -> fixtures = Path.of(require(args[i], value));
                case "--lookups" -> lookups = Path.of(require(args[i], value));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
            i++;
        }
        if (caseName == null || out == null) {
            throw new IllegalArgumentException(
                    "usage: parity-replay --case <name> --out <dir> [--fixtures <dir>] [--lookups <csv>]");
        }
        return new ReplayArgs(caseName, out, fixtures == null ? locateFixtures() : fixtures, lookups);
    }

    Path caseDir() {
        return fixtures.resolve(caseName);
    }

    private static String require(String flag, String value) {
        if (value == null) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return value;
    }

    private static Path locateFixtures() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("fixtures/xferfee");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("fixtures/xferfee not found; pass --fixtures");
    }
}
