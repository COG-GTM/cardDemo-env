package com.carddemo.replay;

import com.carddemo.observability.ChainArtifactsWriter;
import com.carddemo.observability.StepOutcome;
import com.carddemo.observability.XferChainMetrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Replays fixture cases through the counter/return-code model and writes candidate
 * artifacts for tools/parity/compare_counters.py.
 *
 * <pre>parity-replay --fixtures fixtures/xferfee (--case NAME | --all) --out work/parity-java</pre>
 */
public final class ParityReplay {

    private ParityReplay() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("parity-replay: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("parity-replay: " + e);
            System.exit(2);
        }
    }

    static int run(String[] args) throws IOException {
        Path fixtures = Path.of("fixtures/xferfee");
        Path out = Path.of("work/parity-java");
        String caseName = null;
        boolean all = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fixtures" -> fixtures = Path.of(value(args, ++i));
                case "--out" -> out = Path.of(value(args, ++i));
                case "--case" -> caseName = value(args, ++i);
                case "--all" -> all = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (all == (caseName != null)) {
            throw new IllegalArgumentException("pass exactly one of --case NAME or --all");
        }
        List<Path> cases = new ArrayList<>();
        if (all) {
            try (Stream<Path> dirs = Files.list(fixtures)) {
                dirs.filter(d -> Files.isRegularFile(d.resolve("case.json"))).sorted().forEach(cases::add);
            }
        } else {
            cases.add(fixtures.resolve(caseName));
        }
        for (Path dir : cases) {
            replayCase(dir, out.resolve(dir.getFileName().toString()));
        }
        return 0;
    }

    static XferChainMetrics replayCase(Path caseDir, Path out) throws IOException {
        FixtureCase fixture = FixtureCase.load(caseDir);
        XferChainMetrics metrics = new XferChainMetrics(new SimpleMeterRegistry(), Tags.of("case", fixture.name()));
        List<StepOutcome> outcomes = new XferChainReplay().run(fixture, metrics);
        new ChainArtifactsWriter().write(out, outcomes, metrics);
        String rcs = outcomes.stream()
                .map(o -> o.step().name() + "=" + o.returnCode())
                .collect(Collectors.joining(" "));
        System.out.printf("%-14s %s rejects=%d dlq=%d alerts=%d -> %s%n", fixture.name(), rcs,
                metrics.rejects().size(), metrics.deadLetters().size(), metrics.alerts().size(), out);
        return metrics;
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException(args[index - 1] + " needs a value");
        }
        return args[index];
    }
}
