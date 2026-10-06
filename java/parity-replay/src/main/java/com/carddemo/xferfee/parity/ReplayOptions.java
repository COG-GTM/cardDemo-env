package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.events.RunMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code parity-replay --case <name> [--work <dir>] [--mode=inproc|events] [--posting-mode=batch-atomic|per-transfer]}.
 * {@code --out <dir>} is accepted as the foundation alias for {@code <work>/out}.
 */
public record ReplayOptions(
        String caseName,
        Path work,
        Path out,
        Mode mode,
        RunMode postingMode,
        String bootstrapServers,
        String dbUrl,
        String dbUser,
        String dbPassword,
        Duration timeout) {

    public enum Mode { INPROC, EVENTS }

    public Path input() {
        return work.resolve("input");
    }

    public static ReplayOptions parse(String[] args, Map<String, String> env) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String name = arg.substring(2);
            String value;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            } else {
                value = "true";
            }
            values.put(name, value);
        }
        String caseName = values.get("case");
        if (caseName == null) {
            throw new IllegalArgumentException("--case is required");
        }
        Path work = Path.of(values.getOrDefault("work", "work/parity-java/" + caseName));
        Path out = values.containsKey("out") ? Path.of(values.get("out")) : work.resolve("out");
        return new ReplayOptions(
                caseName,
                work,
                out,
                Mode.valueOf(values.getOrDefault("mode", "inproc").toUpperCase().replace('-', '_')),
                RunMode.fromWire(values.getOrDefault("posting-mode", "batch-atomic")),
                values.getOrDefault("bootstrap-servers", env.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")),
                values.getOrDefault("db-url", env.getOrDefault("XFERFEE_DB_URL", "jdbc:postgresql://localhost:5432/carddemo")),
                values.getOrDefault("db-user", env.getOrDefault("XFERFEE_DB_USER", "carddemo")),
                values.getOrDefault("db-password", env.getOrDefault("XFERFEE_DB_PASSWORD", "carddemo")),
                Duration.ofSeconds(Long.parseLong(values.getOrDefault("timeout-seconds", "120"))));
    }
}
