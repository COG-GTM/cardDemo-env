package com.carddemo.xferfee.legacy.io;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * GnuCOBOL {@code ORGANIZATION IS LINE SEQUENTIAL}: each WRITE emits the record with trailing
 * spaces removed, followed by a newline.
 */
public final class LineSequentialFile {

    private LineSequentialFile() {
    }

    public static List<String> read(byte[] data, Charset charset) {
        String text = new String(data, charset);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            throw new IllegalArgumentException("line sequential file does not end with a newline");
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        return lines;
    }

    public static byte[] write(List<String> records, Charset charset) {
        StringBuilder out = new StringBuilder();
        for (String record : records) {
            out.append(record.replaceFirst(" +$", "")).append('\n');
        }
        return out.toString().getBytes(charset);
    }
}
