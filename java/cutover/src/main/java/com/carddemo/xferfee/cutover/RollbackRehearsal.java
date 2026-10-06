package com.carddemo.xferfee.cutover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runbook gate G3: the rollback rehearsal written by {@code tools/cutover/rollback_dryrun.py}
 * ({@code work/cutover/rollback/rollback.json}) passed every check.
 */
public record RollbackRehearsal(boolean present, String status, String finishedAt, List<String> failedChecks) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public RollbackRehearsal {
        failedChecks = List.copyOf(failedChecks);
    }

    public static RollbackRehearsal load(Path rollbackJson) {
        if (!Files.isRegularFile(rollbackJson)) {
            return new RollbackRehearsal(false, "NOT_RUN", null, List.of());
        }
        JsonNode node;
        try {
            node = JSON.readTree(rollbackJson.toFile());
        } catch (IOException e) {
            return new RollbackRehearsal(true, "UNREADABLE", null, List.of());
        }
        List<String> failed = new ArrayList<>();
        for (JsonNode check : node.path("checks")) {
            if (!check.path("ok").asBoolean(false)) {
                failed.add(check.path("name").asText("?"));
            }
        }
        return new RollbackRehearsal(true, node.path("status").asText("UNKNOWN"),
                node.path("finished_at").asText(null), failed);
    }

    public boolean met() {
        return present && "PASS".equals(status) && failedChecks.isEmpty();
    }
}
