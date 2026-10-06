package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeRule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code CTL_XFER_PARM.csv} in the format produced by the
 * parity recorder ({@code psql \copy ... CSV HEADER}): lowercase header, the
 * book id blank-padded to CHAR(10), numerics at column scale, ISO dates.
 */
public final class FeeRuleCsv {

    public static final String HEADER = "book_id,fee_pct,fee_cap,eff_dt,exp_dt";

    private static final int PCT_SCALE = 6;
    private static final int CAP_SCALE = 2;

    private FeeRuleCsv() {
    }

    public static List<FeeRule> read(Path path) throws IOException {
        List<List<String>> records = parse(Files.readString(path, StandardCharsets.UTF_8));
        if (records.isEmpty() || !String.join(",", records.get(0)).equalsIgnoreCase(HEADER)) {
            throw new IOException(path + ": expected header '" + HEADER + "'");
        }
        List<FeeRule> rules = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.size() == 1 && cells.get(0).isEmpty()) {
                continue;
            }
            if (cells.size() != 5) {
                throw new IOException(path + ": record " + (i + 1) + ": expected 5 columns");
            }
            rules.add(new FeeRule(
                    FeeRules.trimPadding(cells.get(0)),
                    new BigDecimal(cells.get(1)),
                    new BigDecimal(cells.get(2)),
                    LocalDate.parse(cells.get(3)),
                    LocalDate.parse(cells.get(4))));
        }
        return rules;
    }

    public static String format(List<FeeRule> rules) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (FeeRule rule : rules) {
            out.append(quote(String.format("%-" + FeeRules.BOOK_ID_LENGTH + "s",
                            rule.bookId())))
                    .append(',').append(rule.feePct().setScale(PCT_SCALE).toPlainString())
                    .append(',').append(rule.feeCap().setScale(CAP_SCALE).toPlainString())
                    .append(',').append(rule.effectiveDate())
                    .append(',').append(rule.expiryDate())
                    .append('\n');
        }
        return out.toString();
    }

    public static void write(Path path, List<FeeRule> rules) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, format(rules), StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        if (value.isEmpty() || value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** RFC 4180 records: quoted fields may contain commas, doubled quotes and line breaks. */
    static List<List<String>> parse(String text) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i < text.length() && text.charAt(i) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i < text.length() && text.charAt(i) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IOException("unterminated quoted field");
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
