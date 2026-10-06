package com.carddemo.xferfee.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader/writer for the db2_before / db2_after snapshots. */
final class Csv {

    private Csv() {
    }

    static List<List<String>> read(Path path) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        String text = Files.readString(path);
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    static String line(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                out.append(value);
            }
        }
        return out.append('\n').toString();
    }
}
