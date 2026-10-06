package com.carddemo.xferfee.reconciliation.parity;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Decodes XFER.FEES records (copybook CVXFR02Y, RECFM=FB LRECL=100) into TransferPosted events. */
public final class Cvxfr02yReader {

    public static final int LRECL = 100;

    private Cvxfr02yReader() {
    }

    public static List<TransferPosted> read(Path dataset) throws IOException {
        byte[] data = Files.readAllBytes(dataset);
        if (data.length % LRECL != 0) {
            throw new IOException(dataset + ": length " + data.length + " is not a multiple of " + LRECL);
        }
        List<TransferPosted> out = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += LRECL) {
            out.add(decode(data, offset));
        }
        return out;
    }

    static TransferPosted decode(byte[] d, int o) {
        return new TransferPosted(
                text(d, o, 16).stripTrailing(),
                date(text(d, o + 16, 10)),
                Long.parseLong(text(d, o + 26, 11)),
                Long.parseLong(text(d, o + 37, 11)),
                text(d, o + 48, 10),
                packed(d, o + 58, 6, 2),
                packed(d, o + 64, 4, 6),
                packed(d, o + 68, 6, 2),
                text(d, o + 74, 1).equals("Y"),
                date(text(d, o + 75, 10)));
    }

    private static String text(byte[] d, int offset, int length) {
        return new String(d, offset, length, StandardCharsets.US_ASCII);
    }

    private static LocalDate date(String value) {
        return value.isBlank() ? null : LocalDate.parse(value.strip());
    }

    static BigDecimal packed(byte[] d, int offset, int length, int scale) {
        StringBuilder digits = new StringBuilder();
        int sign = 0x0C;
        for (int i = 0; i < length; i++) {
            int b = d[offset + i] & 0xFF;
            digits.append((char) ('0' + (b >> 4)));
            if (i == length - 1) {
                sign = b & 0x0F;
            } else {
                digits.append((char) ('0' + (b & 0x0F)));
            }
        }
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return sign == 0x0D ? value.negate() : value;
    }
}
