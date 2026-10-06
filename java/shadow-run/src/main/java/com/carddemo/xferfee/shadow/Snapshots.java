package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** CSV snapshots of the two DB2 tables, in the {@code psql \copy ... CSV HEADER} shape the recorder dumps. */
public final class Snapshots {

    static final String RULES_HEADER = "book_id,fee_pct,fee_cap,eff_dt,exp_dt";
    static final String LEDGER_HEADER =
            "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied";

    private Snapshots() {
    }

    public static List<FeeRule> rules(Path csv) throws IOException {
        List<FeeRule> rules = new ArrayList<>();
        for (String[] f : rows(csv)) {
            rules.add(new FeeRule(f[0].strip(), new BigDecimal(f[1].strip()), new BigDecimal(f[2].strip()),
                    LocalDate.parse(f[3].strip()), LocalDate.parse(f[4].strip())));
        }
        return rules;
    }

    public static List<LedgerEntry> ledger(Path csv) throws IOException {
        List<LedgerEntry> rows = new ArrayList<>();
        if (!Files.exists(csv)) {
            return rows;
        }
        for (String[] f : rows(csv)) {
            rows.add(new LedgerEntry(f[0], LocalDate.parse(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]),
                    f[4].strip(), new BigDecimal(f[5]), new BigDecimal(f[6]), "Y".equals(f[7])));
        }
        return rows;
    }

    static String csv(FeeRule rule) {
        return String.join(",", pad(rule.bookId()), rule.feePct().toPlainString(), rule.feeCap().toPlainString(),
                rule.effectiveDate().toString(), rule.expiryDate().toString());
    }

    static String csv(LedgerEntry row) {
        return String.join(",", row.tranId(), row.tranDate().toString(), Long.toString(row.sourceAccountId()),
                Long.toString(row.targetAccountId()), pad(row.bookId()), row.amount().setScale(2).toPlainString(),
                row.feeAmount().setScale(2).toPlainString(), row.capApplied() ? "Y" : "N");
    }

    /** CHAR(10) / PIC X(10) book id at the legacy edge. */
    static String pad(String bookId) {
        return String.format("%-10s", bookId);
    }

    private static List<String[]> rows(Path csv) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            if (!line.isBlank()) {
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }
}
