package com.carddemo.xferfee.parity;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/** {@code --case <name> --in <dir> --out <dir> [--no-stub-upstream]}. */
record ReplayOptions(String caseName, Path in, Path out, boolean stubUpstream) {

    static ReplayOptions from(ApplicationArguments args) {
        return new ReplayOptions(required(args, "case"), Path.of(required(args, "in")),
                Path.of(required(args, "out")), !args.containsOption("no-stub-upstream"));
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            throw new IllegalArgumentException(
                    "usage: parity-replay --case <name> --in <dir> --out <dir> [--no-stub-upstream]; missing --" + name);
        }
        return values.get(0);
    }
}
