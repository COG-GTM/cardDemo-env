package com.carddemo.xferfee.cutover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Arrays;
import java.util.List;

/**
 * CLI for the runbook's go/no-go step.
 *
 * <pre>
 * java -jar java/cutover/target/cutover.jar [--shadow-root work/shadow] [--parity-root work/parity]
 *     [--rollback work/cutover/rollback/rollback.json] [--required-days 20] [--calendar DAILY|WEEKDAYS]
 *     [--no-rate-change] [--cases a,b,...] [--out work/cutover] [--as-of YYYY-MM-DD]
 *     [--rehearsal-max-age-days 7] [--release-commit SHA] [--allow-fixture-rehearsal]
 *     [--accept-explicit-rehearsal]
 * </pre>
 *
 * Exit code 0 = GO, 1 = NO-GO, 2 = usage error.
 */
public final class CutoverGateApplication {

    static final List<String> ALL_CASES = List.of(
            "default", "under_cap", "at_cap", "rate_change", "zero_amount", "non_transfer", "half_cent");
    static final int DEFAULT_REQUIRED_DAYS = 20;

    private CutoverGateApplication() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    static int run(String[] args) throws IOException {
        Path shadowRoot = Path.of("work/shadow");
        Path parityRoot = Path.of("work/parity");
        Path rollback = Path.of("work/cutover/rollback/rollback.json");
        Path out = Path.of("work/cutover");
        int requiredDays = DEFAULT_REQUIRED_DAYS;
        boolean requireRateChange = true;
        BusinessCalendar calendar = BusinessCalendar.DAILY;
        List<String> cases = ALL_CASES;
        LocalDate asOf = null;
        int rehearsalMaxAgeDays = 7;
        Optional<String> releaseCommit = Optional.empty();
        boolean allowFixtureRehearsal = false;
        boolean acceptExplicitRehearsal = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--shadow-root" -> shadowRoot = Path.of(args[++i]);
                    case "--parity-root" -> parityRoot = Path.of(args[++i]);
                    case "--rollback" -> rollback = Path.of(args[++i]);
                    case "--out" -> out = Path.of(args[++i]);
                    case "--required-days" -> requiredDays = Integer.parseInt(args[++i]);
                    case "--calendar" -> calendar = BusinessCalendar.valueOf(args[++i].toUpperCase());
                    case "--no-rate-change" -> requireRateChange = false;
                    case "--cases" -> cases = Arrays.stream(args[++i].split("[,\\s]+")).map(String::trim)
                            .filter(s -> !s.isEmpty()).toList();
                    case "--as-of" -> asOf = LocalDate.parse(args[++i]);
                    case "--rehearsal-max-age-days" -> rehearsalMaxAgeDays = Integer.parseInt(args[++i]);
                    case "--release-commit" -> releaseCommit = Optional.of(args[++i].trim()).filter(c -> !c.isEmpty());
                    case "--allow-fixture-rehearsal" -> allowFixtureRehearsal = true;
                    case "--accept-explicit-rehearsal" -> acceptExplicitRehearsal = true;
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            if (requiredDays < 1) {
                throw new IllegalArgumentException("--required-days must be positive");
            }
            if (rehearsalMaxAgeDays < 1) {
                throw new IllegalArgumentException("--rehearsal-max-age-days must be positive");
            }
        } catch (RuntimeException e) {
            System.err.println("usage error: " + e.getMessage());
            return 2;
        }
        CutoverReadiness readiness = new CutoverReadiness(
                new ShadowExitCriterion(requiredDays, requireRateChange, calendar)
                        .evaluate(ShadowRunHistory.load(shadowRoot),
                                asOf != null ? asOf : calendar.previous(LocalDate.now())),
                ParityReports.finalReplay(parityRoot, cases),
                RollbackRehearsal.load(rollback, new RollbackRehearsal.Policy(Instant.now(),
                        Duration.ofDays(rehearsalMaxAgeDays), releaseCommit, allowFixtureRehearsal,
                        acceptExplicitRehearsal)));
        Files.createDirectories(out);
        String markdown = readiness.toMarkdown(cases);
        Files.writeString(out.resolve("readiness.md"), markdown);
        Files.writeString(out.resolve("readiness.json"), readiness.toJson(cases));
        System.out.print(markdown);
        return readiness.go() ? 0 : 1;
    }
}
