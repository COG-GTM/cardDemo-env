package com.carddemo.xferfee.replay;

import com.carddemo.xferfee.contracts.replay.ReplayContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** In-memory state for one fixture case: decoded inputs, DB2 tables, outputs, SYSOUT. */
final class CaseContext implements ReplayContext {

    private static final TypeReference<LinkedHashMap<String, String>> RECORD = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    private final String caseName;
    private final LocalDate runDate;
    private final Map<String, List<Map<String, String>>> inputs = new LinkedHashMap<>();
    private final Map<String, List<Map<String, String>>> outputs = new LinkedHashMap<>();
    private final Map<String, List<String>> tableHeaders = new LinkedHashMap<>();
    private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
    private final Map<String, List<String>> sysout = new TreeMap<>();

    CaseContext(ObjectMapper mapper, ReplayOptions options) throws IOException {
        this.mapper = mapper;
        this.caseName = options.caseName();
        JsonNode meta = mapper.readTree(options.caseDir().resolve("case.json").toFile());
        this.runDate = meta.hasNonNull("run_date") ? LocalDate.parse(meta.get("run_date").asText()) : null;
        loadInputs(options.input());
        loadTables(options.caseDir().resolve("db2_before"));
    }

    private void loadInputs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                String dsn = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                inputs.put(dsn, readJsonLines(file));
            }
        }
    }

    private List<Map<String, String>> readJsonLines(Path file) throws IOException {
        List<Map<String, String>> records = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                records.add(mapper.readValue(line, RECORD));
            }
        }
        return records;
    }

    private void loadTables(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
                String table = file.getFileName().toString().replaceFirst("\\.csv$", "");
                List<List<String>> rows = Csv.read(file);
                if (rows.isEmpty()) {
                    continue;
                }
                List<String> header = rows.get(0);
                List<Map<String, String>> data = new ArrayList<>();
                for (List<String> row : rows.subList(1, rows.size())) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int i = 0; i < header.size(); i++) {
                        values.put(header.get(i).toUpperCase(Locale.ROOT), i < row.size() ? row.get(i) : "");
                    }
                    data.add(values);
                }
                tableHeaders.put(table, header);
                tables.put(table, data);
            }
        }
    }

    @Override
    public String caseName() {
        return caseName;
    }

    @Override
    public LocalDate runDate() {
        return runDate;
    }

    @Override
    public List<Map<String, String>> input(String dsn) {
        return inputs.getOrDefault(dsn, List.of());
    }

    @Override
    public List<Map<String, String>> dataset(String dsn) {
        return outputs.getOrDefault(dsn, List.of());
    }

    @Override
    public void writeDataset(String dsn, List<Map<String, String>> records) {
        outputs.put(dsn, new ArrayList<>(records));
    }

    @Override
    public List<Map<String, String>> table(String name) {
        return tables.computeIfAbsent(name, key -> new ArrayList<>());
    }

    @Override
    public void sysout(String step, String line) {
        sysout.computeIfAbsent(step, key -> new ArrayList<>()).add(line);
    }

    void writeTo(Path out) throws IOException {
        Path jsonl = Files.createDirectories(out.resolve("jsonl"));
        for (Map.Entry<String, List<Map<String, String>>> entry : outputs.entrySet()) {
            StringBuilder text = new StringBuilder();
            for (Map<String, String> record : entry.getValue()) {
                text.append(mapper.writeValueAsString(record)).append('\n');
            }
            Files.writeString(jsonl.resolve(entry.getKey() + ".jsonl"), text);
        }
        Path db2 = Files.createDirectories(out.resolve("db2_after"));
        tables.forEach((table, rows) -> {
            List<String> header = tableHeaders.computeIfAbsent(table, key -> rows.isEmpty()
                    ? List.of()
                    : rows.get(0).keySet().stream().map(c -> c.toLowerCase(Locale.ROOT)).toList());
            StringBuilder text = new StringBuilder(Csv.line(header));
            for (Map<String, String> row : rows) {
                text.append(Csv.line(header.stream()
                        .map(column -> row.getOrDefault(column.toUpperCase(Locale.ROOT), ""))
                        .toList()));
            }
            try {
                Files.writeString(db2.resolve(table + ".csv"), text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!sysout.isEmpty()) {
            Path dir = Files.createDirectories(out.resolve("sysout"));
            for (Map.Entry<String, List<String>> entry : sysout.entrySet()) {
                Files.writeString(dir.resolve(entry.getKey() + ".txt"),
                        String.join("\n", entry.getValue()) + "\n");
            }
        }
    }
}
