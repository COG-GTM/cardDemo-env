package com.carddemo.feeschedule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes CTL_XFER_PARM in the fixture CSV layout produced by
 * {@code tools/parity/recorder.py} (psql {@code \copy ... CSV HEADER}).
 */
public final class FeeRuleCsv {

    public static final String MEDIA_TYPE = "text/csv";
    static final String HEADER = "book_id,fee_pct,fee_cap,eff_dt,exp_dt";
    private static final int FEE_PCT_SCALE = 6;
    private static final int FEE_CAP_SCALE = 2;

    private FeeRuleCsv() {
    }

    public static List<FeeRule> read(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<FeeRule> parse(String csv) {
        List<String> lines = csv.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equalsIgnoreCase(HEADER)) {
            throw new IllegalArgumentException("Expected CTL_XFER_PARM header: " + HEADER);
        }
        List<FeeRule> rules = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            if (cols.length != 5) {
                throw new IllegalArgumentException("Expected 5 columns: " + line);
            }
            rules.add(new FeeRule(
                    cols[0],
                    new BigDecimal(cols[1]),
                    new BigDecimal(cols[2]),
                    LocalDate.parse(cols[3]),
                    LocalDate.parse(cols[4])));
        }
        return rules;
    }

    public static String write(List<FeeRule> rules) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (FeeRule rule : rules) {
            out.append(String.format("%-" + FeeRule.BOOK_ID_LENGTH + "s", rule.bookId()))
                    .append(',').append(rule.feePct().setScale(FEE_PCT_SCALE, RoundingMode.UNNECESSARY).toPlainString())
                    .append(',').append(rule.feeCap().setScale(FEE_CAP_SCALE, RoundingMode.UNNECESSARY).toPlainString())
                    .append(',').append(rule.effDt())
                    .append(',').append(rule.expDt())
                    .append('\n');
        }
        return out.toString();
    }
}
