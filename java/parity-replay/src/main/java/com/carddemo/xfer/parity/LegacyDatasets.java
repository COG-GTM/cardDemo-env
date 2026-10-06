package com.carddemo.xfer.parity;

import com.carddemo.xfer.intake.AccountBook;
import com.carddemo.xfer.intake.CardCrossReference;
import com.carddemo.xfer.intake.DailyTransaction;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Reads the fixed-width sequential datasets CBXFR01C consumes. */
final class LegacyDatasets {

    static final int CVTRA05Y_LENGTH = 350;
    static final int CVACT03Y_LENGTH = 50;
    static final int CVACT01Y_LENGTH = 300;

    private static final String POSITIVE = "{ABCDEFGHI";
    private static final String NEGATIVE = "}JKLMNOPQR";
    private static final String NEGATIVE_ASCII = "pqrstuvwxy";

    private LegacyDatasets() {
    }

    /** DALYTRAN (CVTRA05Y). */
    static List<DailyTransaction> dailyTransactions(Path file) throws IOException {
        return read(file, CVTRA05Y_LENGTH, r -> new DailyTransaction(
                r.substring(0, 16),
                r.substring(16, 18),
                r.substring(32, 132),
                zoned(r.substring(132, 143), 2),
                r.substring(262, 278),
                r.substring(278, 304)));
    }

    /** XREFFILE (CVACT03Y). */
    static List<CardCrossReference> cardCrossReferences(Path file) throws IOException {
        return read(file, CVACT03Y_LENGTH, r -> new CardCrossReference(
                r.substring(0, 16),
                r.substring(25, 36)));
    }

    /** ACCTFILE (CVACT01Y). */
    static List<AccountBook> accounts(Path file) throws IOException {
        return read(file, CVACT01Y_LENGTH, r -> new AccountBook(
                r.substring(0, 11),
                r.substring(112, 122)));
    }

    private static <T> List<T> read(Path file, int length, Function<String, T> mapper) throws IOException {
        String data = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        List<T> records = new ArrayList<>();
        for (int offset = 0; offset + length <= data.length(); offset += length) {
            records.add(mapper.apply(data.substring(offset, offset + length)));
        }
        return records;
    }

    /** Zoned decimal with a trailing overpunch sign, as written by IBM COBOL and GnuCOBOL. */
    static BigDecimal zoned(String text, int scale) {
        char last = text.charAt(text.length() - 1);
        String body = text.substring(0, text.length() - 1);
        int sign = 1;
        char digit;
        if (POSITIVE.indexOf(last) >= 0) {
            digit = (char) ('0' + POSITIVE.indexOf(last));
        } else if (NEGATIVE.indexOf(last) >= 0) {
            digit = (char) ('0' + NEGATIVE.indexOf(last));
            sign = -1;
        } else if (NEGATIVE_ASCII.indexOf(last) >= 0) {
            digit = (char) ('0' + NEGATIVE_ASCII.indexOf(last));
            sign = -1;
        } else {
            digit = last;
        }
        BigDecimal value = new BigDecimal(new java.math.BigInteger((body + digit).strip()), scale);
        return sign < 0 ? value.negate() : value;
    }
}
