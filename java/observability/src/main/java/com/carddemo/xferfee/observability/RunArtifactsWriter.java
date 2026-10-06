package com.carddemo.xferfee.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a run's signals in the shapes the parity harness and operators use:
 * {@code sysout/STEP0x0.txt} (legacy text), {@code sysout/STEP0x0.json} (the same counters as
 * JSON), {@code rc.json}, and under {@code observability/} the alerts, DLQ entries and a metrics
 * snapshot. Like the JES log {@code tools/parity/recorder.py} parses, a step skipped by its
 * {@code COND} has no SYSOUT file and no entry in {@code rc.json}.
 */
public class RunArtifactsWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void writeSysout(ChainRunReport report, Path outDir) {
        Path sysout = outDir.resolve("sysout");
        try {
            Files.createDirectories(sysout);
            for (ObservedStep step : report.steps()) {
                if (!step.executed()) {
                    continue;
                }
                String text = step.sysout().isEmpty() ? "" : String.join("\n", step.sysout()) + "\n";
                Files.writeString(sysout.resolve(step.step().name() + ".txt"), text);
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("chain", report.chain());
                json.put("step", step.step().name());
                json.put("program", step.program());
                json.put("returnCode", step.returnCode());
                json.put("severity", step.severity());
                json.put("counters", step.counters());
                json.put("rejected", step.rejected());
                json.put("counterLines", ChainObserver.counterLines(step));
                json.put("sysout", step.sysout());
                mapper.writeValue(sysout.resolve(step.step().name() + ".json").toFile(), json);
            }
            Map<String, Integer> steps = new LinkedHashMap<>();
            for (ObservedStep step : report.steps()) {
                if (step.executed()) {
                    steps.put(step.step().name(), step.returnCode());
                }
            }
            Map<String, Object> rc = new LinkedHashMap<>();
            rc.put("steps", steps);
            rc.put("maxcc", report.maxcc());
            mapper.writeValue(outDir.resolve("rc.json").toFile(), rc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeOperational(List<ChainAlert> alerts, DeadLetterQueue deadLetters, MeterRegistry registry,
            Path outDir) {
        Path dir = outDir.resolve("observability");
        try {
            Files.createDirectories(dir);
            writeJsonLines(dir.resolve("alerts.jsonl"), alerts);
            writeJsonLines(dir.resolve("dlq.jsonl"), deadLetters.entries());
            mapper.writeValue(dir.resolve("metrics.json").toFile(), snapshot(registry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJsonLines(Path path, List<?> rows) throws IOException {
        ObjectMapper compact = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        StringBuilder text = new StringBuilder();
        for (Object row : rows) {
            text.append(compact.writeValueAsString(row)).append('\n');
        }
        Files.writeString(path, text.toString());
    }

    static List<Map<String, Object>> snapshot(MeterRegistry registry) {
        List<Map<String, Object>> meters = new ArrayList<>();
        List<Meter> sorted = new ArrayList<>(registry.getMeters());
        sorted.sort(Comparator.comparing((Meter meter) -> meter.getId().getName())
                .thenComparing(meter -> meter.getId().getTags().toString()));
        for (Meter meter : sorted) {
            if (!meter.getId().getName().startsWith(XferMetrics.PREFIX)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", meter.getId().getName());
            row.put("type", meter.getId().getType().name().toLowerCase());
            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag tag : meter.getId().getTags()) {
                tags.put(tag.getKey(), tag.getValue());
            }
            row.put("tags", tags);
            for (Measurement measurement : meter.measure()) {
                row.put(measurement.getStatistic().getTagValueRepresentation(), measurement.getValue());
            }
            meters.add(row);
        }
        return meters;
    }
}
