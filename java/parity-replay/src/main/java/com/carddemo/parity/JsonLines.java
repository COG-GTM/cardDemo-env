package com.carddemo.parity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One JSON object per line, keyed by copybook field name, values as strings. */
final class JsonLines {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ROW = new TypeReference<>() { };

    private JsonLines() {
    }

    static List<Map<String, String>> read(Path path) {
        try {
            if (!Files.exists(path)) {
                return List.of();
            }
            return Files.readAllLines(path).stream()
                    .filter(line -> !line.isBlank())
                    .<Map<String, String>>map(line -> {
                        try {
                            return JSON.readValue(line, ROW);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path path, List<? extends Map<String, String>> rows) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path)) {
                for (Map<String, String> row : rows) {
                    out.write(JSON.writeValueAsString(row));
                    out.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
