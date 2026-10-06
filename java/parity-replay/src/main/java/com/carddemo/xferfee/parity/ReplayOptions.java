package com.carddemo.xferfee.parity;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code --case <name> --out <dir> [--codec=python|java] [--fixtures <dir>]}.
 *
 * <p>{@code python} (default): inputs are the JSON-lines {@code tools/parity/java_candidate.py}
 * decoded into {@code <out>/input/}, and that script encodes {@code <out>/out/} into the candidate.
 * {@code java}: the legacy-adapter reads the fixture {@code .PS} files and writes
 * {@code <out>/candidate/} itself.
 */
public record ReplayOptions(String caseName, Path out, Codec codec, Path fixtures) {

    public enum Codec { PYTHON, JAVA }

    public Path caseRoot() {
        return fixtures.resolve(caseName);
    }

    static ReplayOptions from(ApplicationArguments args) {
        return new ReplayOptions(
                required(args, "case"),
                Path.of(required(args, "out")),
                Codec.valueOf(optional(args, "codec", "python").toUpperCase()),
                Path.of(optional(args, "fixtures", "fixtures/xferfee")));
    }

    private static String required(ApplicationArguments args, String name) {
        String value = optional(args, name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option --" + name);
        }
        return value;
    }

    private static String optional(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }
}
