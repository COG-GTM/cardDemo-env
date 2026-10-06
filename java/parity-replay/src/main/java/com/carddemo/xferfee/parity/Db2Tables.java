package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The {@code db2_before}/{@code db2_after} CSV dumps (CHAR(10) book ids keep their padding). */
final class Db2Tables {

    static final String CTL_XFER_PARM = "CTL_XFER_PARM.csv";
    static final String XFER_FEE_LEDGER = "XFER_FEE_LEDGER.csv";
    private static final String RULE_HEADER = "book_id,fee_pct,fee_cap,eff_dt,exp_dt";
    private static final String LEDGER_HEADER = "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied";

    private Db2Tables() {
    }

    static List<FeeRule> readRules(Path file) {
        return rows(file).stream().map(c -> new FeeRule(c[0].strip(), new BigDecimal(c[1]), new BigDecimal(c[2]),
                LocalDate.parse(c[3]), LocalDate.parse(c[4]))).toList();
    }

    static List<LedgerEntry> readLedger(Path file) {
        return rows(file).stream().map(c -> new LedgerEntry(c[0].strip(), LocalDate.parse(c[1]), Long.parseLong(c[2]),
                Long.parseLong(c[3]), c[4].strip(), new BigDecimal(c[5]), new BigDecimal(c[6]), "Y".equals(c[7]))).toList();
    }

    static void writeRules(Path file, List<FeeRule> rules) {
        List<String> lines = new ArrayList<>(List.of(RULE_HEADER));
        rules.forEach(r -> lines.add(String.join(",", book(r.bookId()), r.feePct().setScale(6).toPlainString(),
                r.feeCap().setScale(2).toPlainString(), r.effectiveDate().toString(), r.expiryDate().toString())));
        write(file, lines);
    }

    static void writeLedger(Path file, List<LedgerEntry> ledger) {
        List<String> lines = new ArrayList<>(List.of(LEDGER_HEADER));
        ledger.forEach(e -> lines.add(String.join(",", e.tranId(), e.tranDate().toString(),
                Long.toString(e.sourceAccountId()), Long.toString(e.targetAccountId()), book(e.bookId()),
                e.amount().setScale(2).toPlainString(), e.feeAmount().setScale(2).toPlainString(),
                e.capApplied() ? "Y" : "N")));
        write(file, lines);
    }

    private static String book(String bookId) {
        return String.format("%-10s", bookId);
    }

    private static List<String[]> rows(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return lines.stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static void write(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
