package com.carddemo.xferfee.cutover;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the markdown reports written by {@code tools/parity/compare.py --report}. */
public final class ParityReports {

    private static final Pattern HEADING =
            Pattern.compile("^# Parity: (\\S+) / (\\S+) \\S+ (PASS|FAIL)\\s*$", Pattern.MULTILINE);

    private ParityReports() {
    }

    /** Case name to PASS ({@code true}) / FAIL ({@code false}); later headings win. */
    public static Map<String, Boolean> read(Path report) {
        if (!Files.isRegularFile(report)) {
            return Map.of();
        }
        try {
            return parse(Files.readString(report));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Boolean> parse(String markdown) {
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        Matcher matcher = HEADING.matcher(markdown);
        while (matcher.find()) {
            verdicts.put(matcher.group(2), "PASS".equals(matcher.group(3)));
        }
        return verdicts;
    }

    /** Runbook gate G2: every case passes on both the COBOL self-parity and the Java candidate. */
    public static FinalReplay finalReplay(Path parityRoot, List<String> cases) {
        Map<String, Boolean> cobol = read(parityRoot.resolve("report.md"));
        Map<String, CaseResult> results = new LinkedHashMap<>();
        for (String name : cases) {
            Boolean java = javaVerdict(parityRoot, name);
            results.put(name, new CaseResult(Verdict.of(cobol.get(name)), Verdict.of(java)));
        }
        return new FinalReplay(results);
    }

    /**
     * COG-1234 writes {@code work/parity-java/<case>/report.md}; the interim harness wrote
     * {@code work/parity/<case>/java-report.md}. The first report that names the case wins.
     */
    static Boolean javaVerdict(Path parityRoot, String name) {
        Path workRoot = parityRoot.toAbsolutePath().getParent();
        List<Path> candidates = new java.util.ArrayList<>();
        if (workRoot != null) {
            candidates.add(workRoot.resolve("parity-java").resolve(name).resolve("report.md"));
        }
        candidates.add(parityRoot.resolve(name).resolve("java-report.md"));
        for (Path report : candidates) {
            Boolean verdict = read(report).get(name);
            if (verdict != null) {
                return verdict;
            }
        }
        return null;
    }

    public enum Verdict {
        PASS,
        FAIL,
        NOT_RUN;

        static Verdict of(Boolean pass) {
            if (pass == null) {
                return NOT_RUN;
            }
            return pass ? PASS : FAIL;
        }
    }

    public record CaseResult(Verdict cobol, Verdict java) {
        public boolean green() {
            return cobol == Verdict.PASS && java == Verdict.PASS;
        }
    }

    public record FinalReplay(Map<String, CaseResult> cases) {
        public FinalReplay {
            cases = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cases));
        }

        public boolean met() {
            return !cases.isEmpty() && cases.values().stream().allMatch(CaseResult::green);
        }
    }
}
