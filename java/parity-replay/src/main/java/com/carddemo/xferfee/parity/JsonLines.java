package com.carddemo.xferfee.parity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One JSON object per line, all values strings. */
final class JsonLines {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ROW = new TypeReference<>() {
    };

    private JsonLines() {
    }

    static List<Map<String, String>> read(Path path) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) {
                rows.add(MAPPER.readValue(line, ROW));
            }
        }
        return rows;
    }

    static void write(Path path, List<Map<String, String>> rows) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder text = new StringBuilder();
        for (Map<String, String> row : rows) {
            text.append(MAPPER.writeValueAsString(row)).append('\n');
        }
        Files.writeString(path, text);
    }
}
