package org.carddemo.xferfee.replay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal fixed-width (ASCII, DISPLAY) record reader for the fixture input datasets. */
final class FixedRecords {

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";

    private FixedRecords() {
    }

    static List<byte[]> read(Path path, int length) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset + length <= data.length; offset += length) {
            byte[] record = new byte[length];
            System.arraycopy(data, offset, record, 0, length);
            records.add(record);
        }
        return records;
    }

    static String text(byte[] record, int offset, int length) {
        return new String(record, offset, length, StandardCharsets.US_ASCII);
    }

    static long unsigned(byte[] record, int offset, int length) {
        return Long.parseLong(text(record, offset, length).strip());
    }

    /** Zoned decimal with an optional trailing overpunch sign ({@code S9(n)V9(scale)} DISPLAY). */
    static BigDecimal zoned(byte[] record, int offset, int length, int scale) {
        String raw = text(record, offset, length);
        char last = raw.charAt(raw.length() - 1);
        int sign = 1;
        String digits;
        if (POSITIVE.indexOf(last) >= 0) {
            digits = raw.substring(0, raw.length() - 1) + POSITIVE.indexOf(last);
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digits = raw.substring(0, raw.length() - 1) + NEGATIVE.indexOf(last);
            sign = -1;
        } else {
            digits = raw;
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.strip().isEmpty() ? "0" : digits.strip()), scale);
        return sign < 0 ? value.negate() : value;
    }
}
