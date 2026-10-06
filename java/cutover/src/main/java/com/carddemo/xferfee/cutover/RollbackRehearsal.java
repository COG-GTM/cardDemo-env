package com.carddemo.xferfee.cutover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runbook gate G3: the rollback rehearsal written by {@code tools/cutover/rollback_dryrun.py}
 * ({@code work/cutover/rollback/rollback.json}) passed every check, was rehearsed from a
 * Java/adapter-produced generation (not the COBOL fixture stand-in), is recent, and was run
 * on the release commit.
 */
public record RollbackRehearsal(
        boolean present,
        String status,
        String finishedAt,
        String sourceKind,
        String commit,
        List<String> failedChecks,
        List<String> problems) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param now                 reference time for the age check
     * @param maxAge              oldest acceptable rehearsal (runbook: 5 business days, default 7 calendar days)
     * @param releaseCommit       when present, the rehearsal must have run on this commit
     * @param allowFixtureSource  accept a rehearsal whose generation came from the COBOL fixture stand-in
     * @param acceptExplicitSource accept an operator-supplied {@code --source} (operator attests it is adapter egress)
     */
    public record Policy(Instant now, Duration maxAge, Optional<String> releaseCommit, boolean allowFixtureSource,
            boolean acceptExplicitSource) {

        public static Policy defaults() {
            return new Policy(Instant.now(), Duration.ofDays(7), Optional.empty(), false, false);
        }
    }

    public RollbackRehearsal {
        failedChecks = List.copyOf(failedChecks);
        problems = List.copyOf(problems);
    }

    public static RollbackRehearsal load(Path rollbackJson) {
        return load(rollbackJson, Policy.defaults());
    }

    public static RollbackRehearsal load(Path rollbackJson, Policy policy) {
        if (!Files.isRegularFile(rollbackJson)) {
            return new RollbackRehearsal(false, "NOT_RUN", null, null, null, List.of(), List.of("rehearsal NOT_RUN"));
        }
        JsonNode node;
        try {
            node = JSON.readTree(rollbackJson.toFile());
        } catch (IOException e) {
            return new RollbackRehearsal(true, "UNREADABLE", null, null, null, List.of(),
                    List.of("rehearsal UNREADABLE"));
        }
        String status = node.path("status").asText("UNKNOWN");
        String finishedAt = node.path("finished_at").asText(null);
        String sourceKind = node.path("source_kind").asText(null);
        String commit = node.path("commit").asText(null);
        List<String> problems = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        JsonNode checks = node.path("checks");
        if (!checks.isArray() || checks.isEmpty()) {
            problems.add("rehearsal has no checks");
        } else {
            for (JsonNode check : checks) {
                if (!check.path("name").isTextual() || !check.path("ok").isBoolean()) {
                    failed.add("malformed check");
                } else if (!check.path("ok").asBoolean()) {
                    failed.add(check.path("name").asText());
                }
            }
        }
        if (!"PASS".equals(status)) {
            problems.add("rehearsal " + status);
        }
        if (!failed.isEmpty()) {
            problems.add("rehearsal failed " + failed);
        }
        boolean sourceOk = "java".equals(sourceKind)
                || ("explicit".equals(sourceKind) && policy.acceptExplicitSource())
                || ("fixture".equals(sourceKind) && policy.allowFixtureSource());
        if (!sourceOk) {
            problems.add("rehearsal generation came from " + (sourceKind == null ? "an unknown source" : sourceKind)
                    + ", not legacy-adapter egress"
                    + ("explicit".equals(sourceKind) ? " (pass --accept-explicit-rehearsal to attest it)" : ""));
        }
        try {
            Instant finished = finishedAt == null ? null : Instant.parse(finishedAt.replace("+00:00", "Z"));
            if (finished == null) {
                problems.add("rehearsal has no finished_at");
            } else if (finished.isBefore(policy.now().minus(policy.maxAge()))) {
                problems.add("rehearsal finished " + finishedAt + ", older than " + policy.maxAge().toDays() + " days");
            }
        } catch (DateTimeParseException e) {
            problems.add("rehearsal finished_at unparseable: " + finishedAt);
        }
        policy.releaseCommit().ifPresent(release -> {
            if (commit == null || !(commit.startsWith(release) || release.startsWith(commit))) {
                problems.add("rehearsal ran on commit " + commit + ", release is " + release);
            }
        });
        return new RollbackRehearsal(true, status, finishedAt, sourceKind, commit, failed, problems);
    }

    public boolean met() {
        return problems.isEmpty();
    }
}
