package com.carddemo.replay;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal fixed-length record decoding for the ASCII SEQUENTIAL fixture datasets. */
final class FixedRecords {

    private FixedRecords() {
    }

    static List<byte[]> read(Path path, int recordLength) throws IOException {
        byte[] data = Files.readAllBytes(path);
        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset + recordLength <= data.length; offset += recordLength) {
            records.add(Arrays.copyOfRange(data, offset, offset + recordLength));
        }
        return records;
    }

    static String text(byte[] record, int offset, int length) {
        return new String(record, offset, length, StandardCharsets.US_ASCII);
    }

    /** Unsigned DISPLAY numeric; like a MOVE to PIC 9(n), only the low nibble of each byte counts. */
    static long unsigned(byte[] record, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = value * 10 + (record[offset + i] & 0x0F);
        }
        return value;
    }

    /** Signed DISPLAY numeric with a trailing overpunch sign, e.g. PIC S9(09)V99. */
    static BigDecimal signed(byte[] record, int offset, int length, int scale) {
        long value = 0;
        boolean negative = false;
        for (int i = 0; i < length; i++) {
            char c = (char) record[offset + i];
            int digit;
            if (i == length - 1 && !Character.isDigit(c)) {
                if (c == '{') {
                    digit = 0;
                } else if (c >= 'A' && c <= 'I') {
                    digit = c - 'A' + 1;
                } else if (c == '}') {
                    digit = 0;
                    negative = true;
                } else if (c >= 'J' && c <= 'R') {
                    digit = c - 'J' + 1;
                    negative = true;
                } else if (c >= 'p' && c <= 'y') {
                    digit = c - 'p';
                    negative = true;
                } else {
                    digit = c & 0x0F;
                }
            } else {
                digit = c & 0x0F;
            }
            value = value * 10 + digit;
        }
        return BigDecimal.valueOf(negative ? -value : value, scale);
    }
}
