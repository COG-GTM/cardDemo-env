package com.carddemo.xferfee.parity;

import java.nio.file.Path;
import java.util.List;

/** CLI options: {@code --case <name> --out <dir> [--input <dir>] [--fixtures <dir>] [--no-stub-upstream]}. */
public record ReplayOptions(String caseName, Path out, Path input, Path fixtures, boolean stubUpstream) {

    static final String USAGE =
            "usage: parity-replay --case <name> --out <dir> [--input <dir>] [--fixtures <dir>] [--no-stub-upstream]";

    public Path caseDir() {
        return fixtures.resolve(caseName);
    }

    public static ReplayOptions parse(List<String> args) {
        String caseName = null;
        Path out = null;
        Path input = null;
        Path fixtures = Path.of("fixtures", "xferfee");
        boolean stub = true;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--case" -> caseName = value(args, ++i, arg);
                case "--out" -> out = Path.of(value(args, ++i, arg));
                case "--input" -> input = Path.of(value(args, ++i, arg));
                case "--fixtures" -> fixtures = Path.of(value(args, ++i, arg));
                case "--no-stub-upstream" -> stub = false;
                default -> throw new IllegalArgumentException("unknown argument " + arg + "\n" + USAGE);
            }
        }
        if (caseName == null || out == null) {
            throw new IllegalArgumentException(USAGE);
        }
        if (input == null) {
            input = out.toAbsolutePath().getParent().resolve("input");
        }
        return new ReplayOptions(caseName, out, input, fixtures, stub);
    }

    private static String value(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(flag + " needs a value\n" + USAGE);
        }
        return args.get(index);
    }
}
