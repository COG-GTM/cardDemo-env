package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.events.EventJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One JSON document per line: the exchange format with {@code tools/parity/java_candidate.py}. */
final class JsonLines {

    private JsonLines() {
    }

    static <T> List<T> read(Path path, Class<T> type) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<T> rows = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    rows.add(EventJson.read(line, type));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path path, List<?> rows) {
        StringBuilder text = new StringBuilder();
        for (Object row : rows) {
            text.append(EventJson.write(row)).append('\n');
        }
        writeText(path, text.toString());
    }

    static void writeLines(Path path, List<String> lines) {
        writeText(path, lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
    }

    static void writeText(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
