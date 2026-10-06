package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a {@link ChainResult} in the layout {@code tools/parity/compare.py --candidate} reads: {@code datasets/},
 * {@code db2_after/}, {@code sysout/} and {@code rc.json}. Records are byte-compatible with the GnuCOBOL output.
 */
public final class CandidateWriter {

    static final String EXTRACT = "AWS.M2.CARDDEMO.XFER.EXTRACT";
    static final String ACCOUNTS = "AWS.M2.CARDDEMO.ACCTDATA.XFER";
    static final String FEES = "AWS.M2.CARDDEMO.XFER.FEES";
    static final String RECON = "AWS.M2.CARDDEMO.XFER.RECON.RPT";
    private static final String GENERATION = ".G0001V00";

    private CandidateWriter() {
    }

    /**
     * @param rawMaster the input ACCTDATA records in master order, or {@code null}; fields XFERFEE never computed
     *                  are copied from here so the new generation keeps their original sign encoding
     */
    public static void write(ChainResult result, List<String> rawMaster, Path out) throws IOException {
        Path datasets = Files.createDirectories(out.resolve("datasets"));
        dataset(datasets, EXTRACT, result.extract().stream().map(CandidateWriter::extract).toList());
        if (result.accountsOut() != null) {
            Set<Long> sources = new HashSet<>();
            Set<Long> targets = new HashSet<>();
            result.posted().forEach(p -> {
                sources.add(p.sourceAccountId());
                targets.add(p.targetAccountId());
            });
            List<byte[]> records = new ArrayList<>();
            for (int i = 0; i < result.accountsOut().size(); i++) {
                String raw = rawMaster != null && i < rawMaster.size() ? rawMaster.get(i) : null;
                records.add(account(result.accountsOut().get(i), raw, sources, targets));
            }
            dataset(datasets, ACCOUNTS, records);
        }
        if (result.posted() != null) {
            dataset(datasets, FEES, result.posted().stream().map(CandidateWriter::fee).toList());
        }
        if (result.reconReport() != null) {
            String text = String.join("\n", result.reconReport()) + "\n";
            Files.writeString(datasets.resolve(RECON + GENERATION), text, StandardCharsets.ISO_8859_1);
        }

        Path db2 = Files.createDirectories(out.resolve("db2_after"));
        List<String> ctl = new ArrayList<>(List.of(Snapshots.RULES_HEADER));
        result.rules().stream().map(Snapshots::csv).forEach(ctl::add);
        Files.write(db2.resolve("CTL_XFER_PARM.csv"), ctl);
        List<String> ledger = new ArrayList<>(List.of(Snapshots.LEDGER_HEADER));
        result.ledger().stream().map(Snapshots::csv).forEach(ledger::add);
        Files.write(db2.resolve("XFER_FEE_LEDGER.csv"), ledger);

        Path sysout = Files.createDirectories(out.resolve("sysout"));
        for (Map.Entry<String, List<String>> step : result.sysout().entrySet()) {
            Files.write(sysout.resolve(step.getKey() + ".txt"), step.getValue());
        }

        StringBuilder rc = new StringBuilder("{\n  \"steps\": {\n");
        int i = 0;
        for (Map.Entry<String, Integer> step : result.stepRc().entrySet()) {
            rc.append("    \"").append(step.getKey()).append("\": ").append(step.getValue())
                    .append(++i < result.stepRc().size() ? ",\n" : "\n");
        }
        rc.append("  },\n  \"maxcc\": ").append(result.maxcc()).append("\n}\n");
        Files.writeString(out.resolve("rc.json"), rc);
    }

    private static void dataset(Path dir, String dsn, List<byte[]> records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        records.forEach(bytes::writeBytes);
        Files.write(dir.resolve(dsn + GENERATION), bytes.toByteArray());
    }

    /** CVXFR01Y, LRECL 120. */
    static byte[] extract(TransferRequested x) {
        return new FixedRecord(120).text(x.tranId(), 16).text(x.tranDate().toString(), 10)
                .unsigned(x.sourceAccountId(), 11).unsigned(x.targetAccountId(), 11)
                .text(Snapshots.pad(x.bookId()), 10).zoned(x.amount(), 9, 2).text(x.cardNumber(), 16).bytes();
    }

    /**
     * CVACT01Y, LRECL 300, as XFERFEE 3000-WRITE-MASTER moves it out of WS-ACCOUNT-TABLE: the balance and cycle
     * fields of a posted source/target were written by COBOL arithmetic, everything else is the input bytes.
     */
    static byte[] account(Account a, String raw, Set<Long> sources, Set<Long> targets) {
        boolean source = sources.contains(a.accountId());
        boolean target = targets.contains(a.accountId());
        FixedRecord r = new FixedRecord(300).unsigned(a.accountId(), 11).text(a.activeStatus(), 1);
        signed(r, raw, source || target, a.currentBalance(), 12);
        signed(r, raw, false, a.creditLimit(), 24);
        signed(r, raw, false, a.cashCreditLimit(), 36);
        r.text(a.openDate(), 10).text(a.expirationDate(), 10).text(a.reissueDate(), 10);
        signed(r, raw, target, a.currentCycleCredit(), 78);
        signed(r, raw, source, a.currentCycleDebit(), 90);
        return r.text(a.addressZip(), 10).text(a.groupId(), 10).bytes();
    }

    private static void signed(FixedRecord r, String raw, boolean computed, BigDecimal value, int offset) {
        if (computed) {
            r.computedZoned(value, 10, 2);
        } else if (raw != null) {
            r.text(raw.substring(offset, offset + 12), 12);
        } else {
            r.zoned(value, 10, 2);
        }
    }

    /** CVXFR02Y, LRECL 100. */
    static byte[] fee(TransferPosted f) {
        return new FixedRecord(100).text(f.tranId(), 16).text(f.tranDate().toString(), 10)
                .unsigned(f.sourceAccountId(), 11).unsigned(f.targetAccountId(), 11)
                .text(Snapshots.pad(f.bookId()), 10).packed(f.amount(), 9, 2).packed(f.feePct(), 1, 6)
                .packed(f.feeAmount(), 9, 2).text(f.capApplied() ? "Y" : "N", 1)
                .text(f.ruleEffectiveDate().toString(), 10).bytes();
    }
}
