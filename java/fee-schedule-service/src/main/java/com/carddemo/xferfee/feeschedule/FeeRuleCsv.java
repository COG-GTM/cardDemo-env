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
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equalsIgnoreCase(HEADER)) {
            throw new IOException(path + ": expected header '" + HEADER + "'");
        }
        List<FeeRule> rules = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != 5) {
                throw new IOException(path + ":" + (i + 1) + ": expected 5 columns");
            }
            rules.add(new FeeRule(
                    unquote(cells[0]).stripTrailing(),
                    new BigDecimal(cells[1]),
                    new BigDecimal(cells[2]),
                    LocalDate.parse(cells[3]),
                    LocalDate.parse(cells[4])));
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

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }
}
