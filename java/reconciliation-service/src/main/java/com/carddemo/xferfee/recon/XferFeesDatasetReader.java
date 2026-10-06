package com.carddemo.xferfee.recon;

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

/** Decodes the {@code XFER.FEES} dataset (copybook CVXFR02Y, RECFM=FB, LRECL=100). */
public final class XferFeesDatasetReader {

    public static final int RECORD_LENGTH = 100;

    public List<TransferPosted> read(Path dataset, LocalDate businessDate) throws IOException {
        byte[] data = Files.readAllBytes(dataset);
        List<TransferPosted> records = new ArrayList<>();
        for (int offset = 0; offset + RECORD_LENGTH <= data.length; offset += RECORD_LENGTH) {
            records.add(decode(data, offset, businessDate));
        }
        return records;
    }

    static TransferPosted decode(byte[] data, int base, LocalDate businessDate) {
        return new TransferPosted(
                businessDate,
                text(data, base, 16),
                text(data, base + 16, 10),
                display(data, base + 26, 11),
                display(data, base + 37, 11),
                text(data, base + 48, 10),
                packed(data, base + 58, 6, 2),
                packed(data, base + 64, 4, 6),
                packed(data, base + 68, 6, 2),
                data[base + 74] == 'Y',
                text(data, base + 75, 10));
    }

    private static String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII).stripTrailing();
    }

    private static long display(byte[] data, int offset, int length) {
        long value = 0;
        for (int index = offset; index < offset + length; index++) {
            value = value * 10 + (data[index] & 0x0F);
        }
        return value;
    }

    private static BigDecimal packed(byte[] data, int offset, int length, int scale) {
        StringBuilder digits = new StringBuilder();
        for (int index = offset; index < offset + length; index++) {
            int high = (data[index] >> 4) & 0x0F;
            int low = data[index] & 0x0F;
            digits.append(high);
            if (index < offset + length - 1) {
                digits.append(low);
            } else {
                BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
                return low == 0x0D || low == 0x0B ? value.negate() : value;
            }
        }
        throw new IllegalArgumentException("empty packed field");
    }
}
