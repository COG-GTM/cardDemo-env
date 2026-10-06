package com.carddemo.xferfee.replay;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/** The {@code --case/--in/--out} command line. */
public record ReplayOptions(String caseName, Path in, Path out) {

    static ReplayOptions from(ApplicationArguments args) {
        return new ReplayOptions(required(args, "case"), Path.of(required(args, "in")),
                Path.of(required(args, "out")));
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            throw new IllegalArgumentException("missing required option --" + name);
        }
        return values.get(0);
    }
}
