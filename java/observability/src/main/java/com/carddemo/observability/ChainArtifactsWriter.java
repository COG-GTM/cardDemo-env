package com.carddemo.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a candidate run in the layout tools/parity expects: sysout/STEPnnn.txt in the
 * legacy DISPLAY format, sysout/STEPnnn.json counter snapshots, rc.json, plus
 * rejects.jsonl, dlq.jsonl, alerts.jsonl and a metrics.json registry dump.
 */
public final class ChainArtifactsWriter {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ObjectMapper jsonLines = new ObjectMapper();

    public void write(Path out, List<StepOutcome> outcomes, XferChainMetrics metrics) throws IOException {
        Path sysout = out.resolve("sysout");
        Files.createDirectories(sysout);
        Map<String, Integer> steps = new LinkedHashMap<>();
        int maxcc = 0;
        for (StepOutcome outcome : outcomes) {
            String step = outcome.step().name();
            List<String> lines = outcome.sysoutLines();
            Files.writeString(sysout.resolve(step + ".txt"),
                    lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
            json.writeValue(sysout.resolve(step + ".json").toFile(), snapshot(outcome));
            steps.put(step, outcome.returnCode());
            maxcc = Math.max(maxcc, outcome.returnCode());
        }
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", steps);
        rc.put("maxcc", maxcc);
        json.writeValue(out.resolve("rc.json").toFile(), rc);
        writeLines(out.resolve("rejects.jsonl"), metrics.rejects());
        writeLines(out.resolve("dlq.jsonl"), metrics.deadLetters());
        writeLines(out.resolve("alerts.jsonl"), metrics.alerts());
        json.writeValue(out.resolve("metrics.json").toFile(), meters(metrics));
    }

    private Map<String, Object> snapshot(StepOutcome outcome) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("step", outcome.step().name());
        snapshot.put("program", outcome.step().program());
        snapshot.put("returnCode", outcome.returnCode());
        snapshot.put("outcome", com.carddemo.contracts.StepReturnCode.of(outcome.returnCode()).outcome());
        List<Map<String, String>> counters = new ArrayList<>();
        if (outcome.countersDisplayed()) {
            outcome.counters().asMap().forEach((counter, value) -> {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", counter.name());
                entry.put("label", counter.label());
                entry.put("metric", counter.metric());
                entry.put("value", value.toPlainString());
                entry.put("sysout", SysoutFormat.value(counter, value));
                counters.add(entry);
            });
        }
        snapshot.put("counters", counters);
        snapshot.put("messages", outcome.messages());
        return snapshot;
    }

    private List<Map<String, Object>> meters(XferChainMetrics metrics) {
        List<Map<String, Object>> meters = new ArrayList<>();
        metrics.registry().getMeters().stream()
                .sorted(Comparator.comparing((Meter m) -> m.getId().getName())
                        .thenComparing(m -> m.getId().getTags().toString()))
                .forEach(meter -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", meter.getId().getName());
                    entry.put("type", meter.getId().getType().name().toLowerCase());
                    Map<String, String> tags = new LinkedHashMap<>();
                    for (Tag tag : meter.getId().getTags()) {
                        tags.put(tag.getKey(), tag.getValue());
                    }
                    entry.put("tags", tags);
                    for (Measurement measurement : meter.measure()) {
                        entry.put(measurement.getStatistic().getTagValueRepresentation(),
                                BigDecimal.valueOf(measurement.getValue()).stripTrailingZeros().toPlainString());
                    }
                    meters.add(entry);
                });
        return meters;
    }

    private void writeLines(Path path, List<?> records) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Object record : records) {
            text.append(jsonLines.writeValueAsString(record)).append('\n');
        }
        Files.writeString(path, text);
    }
}
