package com.carddemo.xferfee.parity;

import java.nio.file.Path;

/**
 * {@code --case <name> --out <dir>} plus optional {@code --fixtures <root>} (default
 * {@code fixtures/xferfee}) and {@code --input <dir>}: the fixture's input datasets decoded to
 * contract-shaped JSON-lines by {@code tools/parity/java_candidate.py} (default
 * {@code <out>/../input}).
 */
public record ReplayOptions(String caseName, Path out, Path fixtures, Path input) {

    public static ReplayOptions parse(String... args) {
        String caseName = null;
        Path out = null;
        Path fixtures = Path.of("fixtures", "xferfee");
        Path input = null;
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--case" -> caseName = value(args, ++index, arg);
                case "--out" -> out = Path.of(value(args, ++index, arg));
                case "--fixtures" -> fixtures = Path.of(value(args, ++index, arg));
                case "--input" -> input = Path.of(value(args, ++index, arg));
                default -> {
                    if (!arg.startsWith("--spring.") && !arg.startsWith("--logging.")) {
                        throw new IllegalArgumentException("unknown argument: " + arg);
                    }
                }
            }
        }
        if (caseName == null || out == null) {
            throw new IllegalArgumentException(
                    "usage: --case <name> --out <dir> [--fixtures <root>] [--input <dir>]");
        }
        Path resolvedInput = input != null ? input : out.toAbsolutePath().getParent().resolve("input");
        return new ReplayOptions(caseName, out, fixtures, resolvedInput);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    public Path caseDir() {
        return fixtures.resolve(caseName);
    }
}
