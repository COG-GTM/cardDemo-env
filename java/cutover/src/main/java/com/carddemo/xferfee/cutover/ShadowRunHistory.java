package com.carddemo.xferfee.cutover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Loads the shadow-run history written by COG-1242 under {@code work/shadow/<date>/}. */
public final class ShadowRunHistory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ShadowRunHistory() {
    }

    public static List<ShadowDay> load(Path shadowRoot) {
        if (!Files.isDirectory(shadowRoot)) {
            return List.of();
        }
        List<ShadowDay> days = new ArrayList<>();
        try (Stream<Path> entries = Files.list(shadowRoot)) {
            for (Path dir : entries.sorted().toList()) {
                Path report = dir.resolve("report.json");
                if (Files.isRegularFile(report)) {
                    days.add(read(dir, report));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        days.sort(Comparator.comparing(ShadowDay::date));
        return days;
    }

    static ShadowDay read(Path dir, Path report) {
        JsonNode node;
        try {
            node = JSON.readTree(report.toFile());
        } catch (IOException e) {
            return new ShadowDay(dateFromDir(dir), ShadowStatus.ERROR, Set.of(), report);
        }
        LocalDate date = node.hasNonNull("date") ? LocalDate.parse(node.get("date").asText()) : dateFromDir(dir);
        ShadowStatus status = ShadowStatus.parse(node.path("status").asText(null));
        return new ShadowDay(date, status, rateChangeDates(dir.resolve("rules").resolve("CTL_XFER_PARM.csv")), report);
    }

    private static LocalDate dateFromDir(Path dir) {
        try {
            return LocalDate.parse(dir.getFileName().toString());
        } catch (DateTimeParseException e) {
            return LocalDate.MIN;
        }
    }

    /** EFF_DT values that replace an earlier rule for the same BOOK_ID (BR-07 effective dating). */
    static Set<LocalDate> rateChangeDates(Path rulesCsv) {
        if (!Files.isRegularFile(rulesCsv)) {
            return Set.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(rulesCsv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lines.isEmpty()) {
            return Set.of();
        }
        Map<String, Integer> header = new HashMap<>();
        String[] names = lines.get(0).split(",", -1);
        for (int i = 0; i < names.length; i++) {
            header.put(names[i].trim().toUpperCase(), i);
        }
        Integer book = header.get("BOOK_ID");
        Integer eff = header.get("EFF_DT");
        if (book == null || eff == null) {
            return Set.of();
        }
        Map<String, TreeMap<LocalDate, Boolean>> byBook = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            byBook.computeIfAbsent(cols[book].trim(), k -> new TreeMap<>())
                    .put(LocalDate.parse(cols[eff].trim()), Boolean.TRUE);
        }
        Set<LocalDate> changes = new HashSet<>();
        for (TreeMap<LocalDate, Boolean> dates : byBook.values()) {
            dates.keySet().stream().skip(1).forEach(changes::add);
        }
        return changes;
    }
}
