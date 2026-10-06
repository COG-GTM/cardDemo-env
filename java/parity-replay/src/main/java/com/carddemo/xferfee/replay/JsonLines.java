package com.carddemo.xferfee.replay;

import com.carddemo.xferfee.contracts.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One contract record per line, using {@link ContractJson}. */
final class JsonLines {

    private static final ObjectMapper MAPPER = ContractJson.mapper();

    private JsonLines() {
    }

    static <T> List<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<T> rows = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    rows.add(MAPPER.readValue(line, type));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static void write(Path file, List<?> rows) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (Object row : rows) {
                    writer.write(MAPPER.writeValueAsString(row));
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    static void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
