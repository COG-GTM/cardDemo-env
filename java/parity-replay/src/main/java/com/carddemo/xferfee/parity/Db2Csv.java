package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads and writes the {@code db2_before/after/*.csv} dumps in the recorder's psql CSV format. */
final class Db2Csv {

    static final String CTL_XFER_PARM = "CTL_XFER_PARM";
    static final String XFER_FEE_LEDGER = "XFER_FEE_LEDGER";
    private static final String PARM_HEADER = "book_id,fee_pct,fee_cap,eff_dt,exp_dt";
    private static final String LEDGER_HEADER =
            "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied";
    private static final int BOOK_WIDTH = 10;

    private Db2Csv() {
    }

    static List<FeeRule> readFeeRules(Path csv) throws IOException {
        List<FeeRule> rules = new ArrayList<>();
        for (Map<String, String> row : rows(csv)) {
            rules.add(new FeeRule(
                    row.get("book_id").strip(),
                    new BigDecimal(row.get("fee_pct")),
                    new BigDecimal(row.get("fee_cap")),
                    LocalDate.parse(row.get("eff_dt")),
                    LocalDate.parse(row.get("exp_dt"))));
        }
        return rules;
    }

    static List<LedgerEntry> readLedger(Path csv) throws IOException {
        List<LedgerEntry> ledger = new ArrayList<>();
        for (Map<String, String> row : rows(csv)) {
            ledger.add(new LedgerEntry(
                    row.get("tran_id").strip(),
                    LocalDate.parse(row.get("tran_dt")),
                    Long.parseLong(row.get("src_acct_id")),
                    Long.parseLong(row.get("tgt_acct_id")),
                    row.get("book_id").strip(),
                    new BigDecimal(row.get("tran_amt")),
                    new BigDecimal(row.get("fee_amt")),
                    "Y".equals(row.get("cap_applied").strip())));
        }
        return ledger;
    }

    static void writeFeeRules(Path csv, List<FeeRule> rules) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(PARM_HEADER);
        for (FeeRule rule : rules) {
            lines.add(String.join(",",
                    book(rule.bookId()),
                    decimal(rule.feePct(), 6),
                    decimal(rule.feeCap(), 2),
                    rule.effectiveDate().toString(),
                    rule.expiryDate().toString()));
        }
        write(csv, lines);
    }

    static void writeLedger(Path csv, List<LedgerEntry> ledger) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(LEDGER_HEADER);
        for (LedgerEntry entry : ledger) {
            lines.add(String.join(",",
                    entry.tranId(),
                    entry.tranDate().toString(),
                    Long.toString(entry.sourceAccountId()),
                    Long.toString(entry.targetAccountId()),
                    book(entry.bookId()),
                    decimal(entry.amount(), 2),
                    decimal(entry.feeAmount(), 2),
                    entry.capApplied() ? "Y" : "N"));
        }
        write(csv, lines);
    }

    private static List<Map<String, String>> rows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        String[] header = lines.get(0).split(",", -1);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i].toLowerCase(Locale.ROOT), i < cells.length ? cells[i] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static String book(String bookId) {
        return String.format("%-" + BOOK_WIDTH + "s", bookId);
    }

    /** Pads to the column scale for byte-equal dumps; never rounds (a wider scale is written as-is). */
    private static String decimal(BigDecimal value, int scale) {
        return (value.scale() < scale ? value.setScale(scale) : value).toPlainString();
    }

    private static void write(Path csv, List<String> lines) throws IOException {
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, String.join("\n", lines) + "\n");
    }
}
