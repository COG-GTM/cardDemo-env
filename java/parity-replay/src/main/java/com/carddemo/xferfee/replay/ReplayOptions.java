package com.carddemo.xferfee.replay;

import java.nio.file.Path;

/** CLI: {@code --case <name> --out <dir> [--fixtures <dir>] [--input <dir>]}. */
record ReplayOptions(String caseName, Path out, Path fixtures, Path input) {

    static ReplayOptions parse(String[] args) {
        String caseName = null;
        Path out = null;
        Path fixtures = Path.of("fixtures", "xferfee");
        Path input = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!name.startsWith("--")) {
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + name);
                }
                value = args[++i];
            }
            switch (name) {
                case "--case" -> caseName = value;
                case "--out" -> out = Path.of(value);
                case "--fixtures" -> fixtures = Path.of(value);
                case "--input" -> input = Path.of(value);
                default -> throw new IllegalArgumentException("unknown option " + name);
            }
        }
        if (caseName == null || out == null) {
            throw new IllegalArgumentException(
                    "usage: --case <name> --out <dir> [--fixtures <dir>] [--input <dir>]");
        }
        return new ReplayOptions(caseName, out, fixtures, input == null ? out.resolve("input") : input);
    }

    Path caseDir() {
        return fixtures.resolve(caseName);
    }
}
