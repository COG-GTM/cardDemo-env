package com.carddemo.xferfee.parity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JSON-lines (one contract record per line) and plain text files under the replay dirs. */
final class ReplayFiles {

    private final ObjectMapper mapper;

    ReplayFiles(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    <T> List<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<T> rows = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    rows.add(mapper.readValue(line, type));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    void writeJsonLines(Path file, List<?> rows) {
        List<String> lines = new ArrayList<>();
        try {
            for (Object row : rows) {
                lines.add(mapper.writeValueAsString(row));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialise " + file, e);
        }
        writeLines(file, lines);
    }

    void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
