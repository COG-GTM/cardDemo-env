package com.carddemo.xferfee.legacy.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RECFM=FB sequential datasets: records back to back, no delimiters. */
public final class FixedLengthFile {

    private FixedLengthFile() {
    }

    public static List<byte[]> read(Path path, int lrecl) throws IOException {
        return split(Files.readAllBytes(path), lrecl, path.toString());
    }

    public static List<byte[]> split(byte[] data, int lrecl, String source) {
        if (data.length % lrecl != 0) {
            throw new IllegalArgumentException(source + ": " + data.length + " bytes is not a multiple of LRECL " + lrecl);
        }
        List<byte[]> records = new ArrayList<>(data.length / lrecl);
        for (int offset = 0; offset < data.length; offset += lrecl) {
            records.add(Arrays.copyOfRange(data, offset, offset + lrecl));
        }
        return records;
    }

    public static void write(OutputStream out, List<byte[]> records) throws IOException {
        for (byte[] record : records) {
            out.write(record);
        }
    }
}
