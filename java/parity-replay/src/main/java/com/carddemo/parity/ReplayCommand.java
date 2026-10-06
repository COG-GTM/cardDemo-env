package com.carddemo.parity;

import com.carddemo.contracts.Account;
import com.carddemo.contracts.TransferPosted;
import com.carddemo.contracts.TransferRequested;
import com.carddemo.posting.AccountRepository;
import com.carddemo.posting.CardXrefRepository;
import com.carddemo.posting.FeeLedgerRepository;
import com.carddemo.posting.PostingRunner;
import com.carddemo.posting.RunResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code --in <jsonl dir> --db2-before <dir> --out <dir>}: seeds the posting database from the
 * fixture, posts the transfers and writes jsonl/, db2_after/, sysout/STEP020.txt and rc.json.
 */
@Component
public class ReplayCommand implements ApplicationRunner {

    static final String FEES = "AWS.M2.CARDDEMO.XFER.FEES";
    static final String ACCOUNT_MASTER = "AWS.M2.CARDDEMO.ACCTDATA.XFER";

    private final AccountRepository accounts;
    private final CardXrefRepository cardXref;
    private final FeeLedgerRepository ledger;
    private final CsvFeeSchedule feeSchedule;
    private final PostingRunner runner;
    private final JdbcTemplate jdbc;

    public ReplayCommand(AccountRepository accounts, CardXrefRepository cardXref, FeeLedgerRepository ledger,
            CsvFeeSchedule feeSchedule, PostingRunner runner, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.cardXref = cardXref;
        this.ledger = ledger;
        this.feeSchedule = feeSchedule;
        this.runner = runner;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("in")) {
            return;
        }
        replay(Path.of(args.getOptionValues("in").get(0)), Path.of(args.getOptionValues("db2-before").get(0)),
                Path.of(args.getOptionValues("out").get(0)));
    }

    RunResult replay(Path in, Path db2Before, Path out) {
        feeSchedule.load(db2Before.resolve("CTL_XFER_PARM.csv"));
        seedLedger(db2Before.resolve("XFER_FEE_LEDGER.csv"));
        JsonLines.read(in.resolve("ACCTDATA.jsonl")).forEach(row -> accounts.insert(account(row)));
        JsonLines.read(in.resolve("CARDXREF.jsonl")).forEach(row -> cardXref.insert(row.get("XREF-CARD-NUM"),
                Long.parseLong(row.get("XREF-CUST-ID")), Long.parseLong(row.get("XREF-ACCT-ID"))));
        List<TransferRequested> transfers = JsonLines.read(in.resolve("XFER.EXTRACT.jsonl")).stream()
                .map(ReplayCommand::transfer)
                .toList();

        RunResult result = runner.run(transfers);

        JsonLines.write(out.resolve("jsonl").resolve(FEES + ".jsonl"),
                result.posted().stream().map(ReplayCommand::feeRecord).toList());
        List<Map<String, String>> master = result.committed()
                ? accounts.findAll().stream().map(ReplayCommand::accountRecord).toList()
                : List.of();
        JsonLines.write(out.resolve("jsonl").resolve(ACCOUNT_MASTER + ".jsonl"), master);
        writeDb2After(db2Before, out.resolve("db2_after"));
        writeSysout(result, out.resolve("sysout").resolve("STEP020.txt"));
        writeText(out.resolve("rc.json"), String.format(
                "{\"steps\": {\"STEP020\": %d}, \"maxcc\": %d}%n", result.returnCode(), result.returnCode()));
        return result;
    }

    private void seedLedger(Path csv) {
        for (String[] c : csvRows(csv)) {
            jdbc.update("INSERT INTO XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, "
                            + "TRAN_AMT, FEE_AMT, CAP_APPLIED) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    c[0], java.sql.Date.valueOf(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3]), c[4],
                    new BigDecimal(c[5]), new BigDecimal(c[6]), c[7]);
        }
    }

    private void writeDb2After(Path db2Before, Path db2After) {
        StringBuilder csv = new StringBuilder(
                "tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied\n");
        for (Map<String, Object> row : ledger.findAll()) {
            csv.append(String.join(",", str(row.get("TRAN_ID")), str(row.get("TRAN_DT")),
                    str(row.get("SRC_ACCT_ID")), str(row.get("TGT_ACCT_ID")), str(row.get("BOOK_ID")),
                    str(row.get("TRAN_AMT")), str(row.get("FEE_AMT")), str(row.get("CAP_APPLIED")))).append('\n');
        }
        writeText(db2After.resolve("XFER_FEE_LEDGER.csv"), csv.toString());
        // Posting only reads fee rules; the fee-schedule stand-in leaves CTL_XFER_PARM untouched.
        try {
            Files.copy(db2Before.resolve("CTL_XFER_PARM.csv"), db2After.resolve("CTL_XFER_PARM.csv"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeSysout(RunResult result, Path path) {
        List<String> lines = new ArrayList<>(result.diagnostics());
        if (result.returnCode() == RunResult.RC_ABEND) {
            lines.add("XFERFEE: 9999-ABEND-PROGRAM");
        } else {
            lines.add(String.format("XFERFEE: TRANSFERS POSTED %09d", result.posted().size()));
            lines.add("XFERFEE: TOTAL FEES " + LegacyDisplay.signed(result.feeTotal(), 11, 2));
        }
        writeText(path, String.join("\n", lines) + "\n");
    }

    private static TransferRequested transfer(Map<String, String> row) {
        return new TransferRequested(row.get("XFR-TRAN-ID"), LocalDate.parse(row.get("XFR-TRAN-DT")),
                Long.parseLong(row.get("XFR-SRC-ACCT-ID")), Long.parseLong(row.get("XFR-TGT-ACCT-ID")),
                row.get("XFR-BOOK-ID"), new BigDecimal(row.get("XFR-TRAN-AMT")), row.get("XFR-CARD-NUM"));
    }

    private static Account account(Map<String, String> row) {
        return new Account(Long.parseLong(row.get("ACCT-ID")), row.get("ACCT-ACTIVE-STATUS"),
                new BigDecimal(row.get("ACCT-CURR-BAL")), new BigDecimal(row.get("ACCT-CREDIT-LIMIT")),
                new BigDecimal(row.get("ACCT-CASH-CREDIT-LIMIT")), row.get("ACCT-OPEN-DATE"),
                row.get("ACCT-EXPIRAION-DATE"), row.get("ACCT-REISSUE-DATE"),
                new BigDecimal(row.get("ACCT-CURR-CYC-CREDIT")), new BigDecimal(row.get("ACCT-CURR-CYC-DEBIT")),
                row.get("ACCT-ADDR-ZIP"), row.get("ACCT-GROUP-ID"));
    }

    private static Map<String, String> accountRecord(Account a) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("ACCT-ID", Long.toString(a.acctId()));
        row.put("ACCT-ACTIVE-STATUS", a.activeStatus());
        row.put("ACCT-CURR-BAL", a.currBal().toPlainString());
        row.put("ACCT-CREDIT-LIMIT", a.creditLimit().toPlainString());
        row.put("ACCT-CASH-CREDIT-LIMIT", a.cashCreditLimit().toPlainString());
        row.put("ACCT-OPEN-DATE", a.openDate());
        row.put("ACCT-EXPIRAION-DATE", a.expirationDate());
        row.put("ACCT-REISSUE-DATE", a.reissueDate());
        row.put("ACCT-CURR-CYC-CREDIT", a.currCycCredit().toPlainString());
        row.put("ACCT-CURR-CYC-DEBIT", a.currCycDebit().toPlainString());
        row.put("ACCT-ADDR-ZIP", a.addrZip());
        row.put("ACCT-GROUP-ID", a.groupId());
        return row;
    }

    private static Map<String, String> feeRecord(TransferPosted p) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("XFE-TRAN-ID", p.tranId());
        row.put("XFE-TRAN-DT", p.tranDate().toString());
        row.put("XFE-SRC-ACCT-ID", Long.toString(p.sourceAccountId()));
        row.put("XFE-TGT-ACCT-ID", Long.toString(p.targetAccountId()));
        row.put("XFE-BOOK-ID", p.bookId());
        row.put("XFE-TRAN-AMT", p.amount().toPlainString());
        row.put("XFE-FEE-PCT", p.feePct().toPlainString());
        row.put("XFE-FEE-AMT", p.feeAmount().toPlainString());
        row.put("XFE-CAP-APPLIED", p.capAppliedFlag());
        row.put("XFE-RULE-EFF-DT", p.ruleEffectiveDate().toString());
        return row;
    }

    private static List<String[]> csvRows(Path csv) {
        try {
            if (!Files.exists(csv)) {
                return List.of();
            }
            return Files.readAllLines(csv).stream().skip(1).filter(l -> !l.isBlank())
                    .map(l -> l.split(",", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(Object value) {
        return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
    }

    private static void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
