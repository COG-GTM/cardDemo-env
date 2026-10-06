package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs XFERFEEP (STEP010..STEP030) in-process against whichever contract beans exist and writes
 * the raw Java output under {@code --out}: {@code datasets/<DSN>.jsonl} (or {@code .txt} for the
 * report), {@code db2_after/*.csv}, {@code sysout/STEPnnn.txt}, {@code rc.json},
 * {@code rejected.jsonl} and {@code replay.json} (status per step).
 *
 * <p>A missing step writes nothing. With upstream stubbing (default) a missing
 * {@link TransferIntake} is replaced by the recorded {@code XFER.EXTRACT} and a missing
 * {@link AccountPosting} by the recorded {@code XFER.FEES}, as inputs to the next step only.
 */
@Component
class ChainReplay {

    static final String HLQ = "AWS.M2.CARDDEMO.";

    private final ObjectProvider<TransferIntake> intake;
    private final ObjectProvider<AccountPosting> posting;
    private final ObjectProvider<Reconciliation> reconciliation;
    private final ObjectProvider<FeeSchedule> feeSchedule;
    private final ReplayFiles files;

    ChainReplay(ObjectProvider<TransferIntake> intake, ObjectProvider<AccountPosting> posting,
            ObjectProvider<Reconciliation> reconciliation, ObjectProvider<FeeSchedule> feeSchedule,
            ObjectMapper mapper) {
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
        this.feeSchedule = feeSchedule;
        this.files = new ReplayFiles(mapper);
    }

    Map<String, String> run(ReplayOptions options) {
        Path in = options.in();
        Path out = options.out();
        Map<String, Integer> codes = new LinkedHashMap<>();
        Map<String, String> status = new LinkedHashMap<>();
        List<TransferRejected> rejected = new ArrayList<>();

        FeeSchedule schedule = feeSchedule.getIfAvailable();
        if (schedule != null) {
            schedule.seed(files.read(in.resolve("CTL_XFER_PARM.jsonl"), FeeRule.class));
        }
        List<Account> accounts = files.read(in.resolve("ACCTDATA.jsonl"), Account.class);

        // STEP010 CBXFR01C
        List<TransferRequested> requested = null;
        TransferIntake intakeStep = intake.getIfAvailable();
        if (intakeStep != null) {
            TransferIntake.IntakeResult result = intakeStep.extract(
                    files.read(in.resolve("DALYTRAN.jsonl"), DailyTransaction.class),
                    files.read(in.resolve("CARDXREF.jsonl"), CardXref.class), accounts);
            record(result.report(), codes, status, out);
            files.writeJsonLines(out.resolve("datasets/" + HLQ + "XFER.EXTRACT.jsonl"), result.requested());
            rejected.addAll(result.rejected());
            requested = result.requested();
        } else if (options.stubUpstream()) {
            status.put("STEP010", "NOT IMPLEMENTED: stubbed with recorded XFER.EXTRACT");
            requested = files.read(in.resolve("recorded/XFER.EXTRACT.jsonl"), TransferRequested.class);
        } else {
            status.put("STEP010", "NOT IMPLEMENTED: no TransferIntake bean");
        }

        // STEP020 XFERFEE
        List<TransferPosted> posted = null;
        int postingRc = 0;
        AccountPosting postingStep = posting.getIfAvailable();
        if (requested == null) {
            status.put("STEP020", "NOT RUN: no STEP010 output");
        } else if (postingStep != null) {
            AccountPosting.PostingResult result = postingStep.post(requested, accounts,
                    files.read(in.resolve("XFER_FEE_LEDGER.jsonl"), LedgerEntry.class));
            postingRc = record(result.report(), codes, status, out);
            if (postingRc <= 4) {
                // DISP=(NEW,CATLG,DELETE): an abended step keeps no new generations.
                files.writeJsonLines(out.resolve("datasets/" + HLQ + "ACCTDATA.XFER.jsonl"), result.accountMasterAfter());
                files.writeJsonLines(out.resolve("datasets/" + HLQ + "XFER.FEES.jsonl"), result.posted());
            }
            files.writeLines(out.resolve("db2_after/XFER_FEE_LEDGER.csv"), ledgerCsv(result.ledgerAfter()));
            rejected.addAll(result.rejected());
            posted = result.posted();
        } else if (options.stubUpstream()) {
            status.put("STEP020", "NOT IMPLEMENTED: stubbed with recorded XFER.FEES");
            posted = files.read(in.resolve("recorded/XFER.FEES.jsonl"), TransferPosted.class);
        } else {
            status.put("STEP020", "NOT IMPLEMENTED: no AccountPosting bean");
        }

        // STEP030 CBXFR03C, COND=(4,LT,STEP020)
        Reconciliation reconStep = reconciliation.getIfAvailable();
        if (posted == null) {
            status.put("STEP030", "NOT RUN: no STEP020 output");
        } else if (postingRc > 4) {
            status.put("STEP030", "BYPASSED: COND=(4,LT,STEP020)");
        } else if (reconStep != null) {
            Reconciliation.ReconResult result = reconStep.reconcile(posted);
            record(result.report(), codes, status, out);
            files.writeLines(out.resolve("datasets/" + HLQ + "XFER.RECON.RPT.txt"), result.reportLines());
        } else {
            status.put("STEP030", "NOT IMPLEMENTED: no Reconciliation bean");
        }

        if (schedule != null) {
            files.writeLines(out.resolve("db2_after/CTL_XFER_PARM.csv"), feeRuleCsv(schedule.rules()));
        }
        files.writeJsonLines(out.resolve("rejected.jsonl"), rejected);
        int maxcc = codes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", codes);
        rc.put("maxcc", maxcc);
        files.writeJson(out.resolve("rc.json"), rc);
        files.writeJson(out.resolve("replay.json"), status);
        return status;
    }

    private int record(StepReport report, Map<String, Integer> codes, Map<String, String> status, Path out) {
        codes.put(report.step(), report.returnCode());
        status.put(report.step(), "RC=" + report.returnCode());
        files.writeLines(out.resolve("sysout/" + report.step() + ".txt"), report.sysout());
        return report.returnCode();
    }

    static List<String> ledgerCsv(List<LedgerEntry> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied");
        for (LedgerEntry e : rows) {
            lines.add(String.join(",", e.tranId(), e.tranDate().toString(), Long.toString(e.sourceAccountId()),
                    Long.toString(e.targetAccountId()), charN(e.bookId(), 10), money(e.amount()),
                    money(e.feeAmount()), e.capApplied() ? "Y" : "N"));
        }
        return lines;
    }

    static List<String> feeRuleCsv(List<FeeRule> rules) {
        List<String> lines = new ArrayList<>();
        lines.add("book_id,fee_pct,fee_cap,eff_dt,exp_dt");
        for (FeeRule r : rules) {
            lines.add(String.join(",", charN(r.bookId(), 10), r.feePct().setScale(6).toPlainString(),
                    money(r.feeCap()), r.effectiveDate().toString(), r.expiryDate().toString()));
        }
        return lines;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    /** CHAR(n) as DB2 returns it: right-padded with blanks. */
    private static String charN(String value, int length) {
        return value.length() >= length ? value : value + " ".repeat(length - value.length());
    }
}
