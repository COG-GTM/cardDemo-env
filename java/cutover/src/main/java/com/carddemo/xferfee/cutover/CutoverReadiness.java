package com.carddemo.xferfee.cutover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GO / NO-GO for the cut-over change, combining gates G1 (shadow), G2 (replay), G3 (rollback). */
public record CutoverReadiness(
        ShadowExitCriterion.Result shadow,
        ParityReports.FinalReplay replay,
        RollbackRehearsal rollback) {

    public boolean go() {
        return shadow.met() && replay.met() && rollback.met();
    }

    public List<String> blockers() {
        List<String> blockers = new ArrayList<>();
        shadow.reasons().forEach(r -> blockers.add("G1 shadow: " + r));
        replay.cases().forEach((name, result) -> {
            if (!result.green()) {
                blockers.add("G2 replay: " + name + " cobol=" + result.cobol() + " java=" + result.java());
            }
        });
        if (replay.cases().isEmpty()) {
            blockers.add("G2 replay: no cases evaluated");
        }
        if (!rollback.met()) {
            rollback.problems().forEach(problem -> blockers.add("G3 rollback: " + problem));
        }
        return blockers;
    }

    public String toMarkdown(List<String> caseOrder) {
        StringBuilder md = new StringBuilder();
        md.append("# xferfee cut-over readiness: ").append(go() ? "GO" : "NO-GO").append("\n\n");
        md.append("| Gate | Result | Detail |\n|---|---|---|\n");
        md.append("| G1 shadow-run exit criterion | ").append(mark(shadow.met())).append(" | ")
                .append(shadow.cleanStreak()).append("/").append(shadow.requiredDays())
                .append(" consecutive clean days");
        if (shadow.windowStart() != null) {
            md.append(" (").append(shadow.windowStart()).append(" .. ").append(shadow.windowEnd()).append(")");
        }
        md.append(", rate change in window: ").append(shadow.rateChangeCovered() ? "yes" : "no").append(" |\n");
        long green = replay.cases().values().stream().filter(ParityReports.CaseResult::green).count();
        md.append("| G2 final parity replay | ").append(mark(replay.met())).append(" | ")
                .append(green).append("/").append(replay.cases().size()).append(" cases green on COBOL and Java |\n");
        md.append("| G3 rollback rehearsal | ").append(mark(rollback.met())).append(" | ")
                .append(rollback.status());
        if (rollback.finishedAt() != null) {
            md.append(" at ").append(rollback.finishedAt());
        }
        if (rollback.sourceKind() != null) {
            md.append(", generation: ").append(rollback.sourceKind());
        }
        if (rollback.commit() != null) {
            md.append(", commit ").append(rollback.commit(), 0, Math.min(12, rollback.commit().length()));
        }
        md.append(" |\n\n## Final replay per case\n\n| Case | COBOL `make parity` | Java `make parity-java` |\n|---|---|---|\n");
        for (String name : caseOrder) {
            ParityReports.CaseResult result = replay.cases().get(name);
            if (result != null) {
                md.append("| ").append(name).append(" | ").append(result.cobol()).append(" | ")
                        .append(result.java()).append(" |\n");
            }
        }
        List<String> blockers = blockers();
        md.append("\n## Blockers\n\n");
        if (blockers.isEmpty()) {
            md.append("(none)\n");
        } else {
            blockers.forEach(b -> md.append("- ").append(b).append('\n'));
        }
        return md.toString();
    }

    public String toJson(List<String> caseOrder) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("verdict", go() ? "GO" : "NO-GO");
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("met", shadow.met());
        g1.put("required_days", shadow.requiredDays());
        g1.put("clean_streak", shadow.cleanStreak());
        g1.put("window_start", shadow.windowStart() == null ? null : shadow.windowStart().toString());
        g1.put("window_end", shadow.windowEnd() == null ? null : shadow.windowEnd().toString());
        g1.put("rate_change_covered", shadow.rateChangeCovered());
        root.put("shadow", g1);
        Map<String, Object> cases = new LinkedHashMap<>();
        for (String name : caseOrder) {
            ParityReports.CaseResult result = replay.cases().get(name);
            if (result != null) {
                cases.put(name, Map.of("cobol", result.cobol().name(), "java", result.java().name()));
            }
        }
        root.put("replay", Map.of("met", replay.met(), "cases", cases));
        root.put("rollback", Map.of("met", rollback.met(), "status", rollback.status(),
                "failed_checks", rollback.failedChecks(),
                "problems", rollback.problems()));
        root.put("blockers", blockers());
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mark(boolean met) {
        return met ? "MET" : "NOT MET";
    }
}
