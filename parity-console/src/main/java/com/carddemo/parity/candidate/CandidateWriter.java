package com.carddemo.parity.candidate;

import com.carddemo.parity.engine.CaseInputs;
import com.carddemo.parity.engine.FeeRounding;
import com.carddemo.parity.engine.LedgerRow;
import com.carddemo.parity.engine.TransferOutcome;
import com.carddemo.parity.engine.XferFeeEngine;
import com.carddemo.parity.records.Account;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs a whole fixture case through the Java engine and writes a candidate directory in the layout
 * {@code tools/parity/compare.py --candidate} expects (datasets/, db2_after/, sysout/, rc.json).
 */
public final class CandidateWriter {

    private static final String HLQ = "AWS.M2.CARDDEMO.";
    private static final String GEN = ".G0001V00";

    private CandidateWriter() {
    }

    public static void write(CaseInputs inputs, FeeRounding rounding, Path out) throws IOException {
        XferFeeEngine engine = inputs.newEngine(rounding);
        List<TransferOutcome> outcomes = inputs.feed().stream().map(engine::process).toList();
        List<TransferOutcome> posted = outcomes.stream().filter(TransferOutcome::selected).toList();
        List<String> extractMessages = outcomes.stream()
                .flatMap(o -> o.extractMessages().stream()).toList();
        int unmatched = extractMessages.size();

        deleteRecursively(out);
        Path datasets = Files.createDirectories(out.resolve("datasets"));
        Path sysout = Files.createDirectories(out.resolve("sysout"));
        Path db2 = Files.createDirectories(out.resolve("db2_after"));

        Files.write(datasets.resolve(HLQ + "XFER.EXTRACT" + GEN),
                concat(posted.stream().map(TransferOutcome::extractRecord).toList()));
        List<String> step010 = new ArrayList<>(extractMessages);
        step010.add(String.format("CBXFR01C: RECORDS READ %09d", inputs.feed().size()));
        step010.add(String.format("CBXFR01C: TRANSFERS SELECTED %09d", posted.size()));
        step010.add(String.format("CBXFR01C: UNMATCHED CARDS %09d", unmatched));
        Files.write(sysout.resolve("STEP010.txt"), step010);

        Map<String, Integer> steps = new LinkedHashMap<>();
        List<LedgerRow> ledger = List.of();
        if (unmatched > 0) {
            steps.put("STEP010", 4);
        } else {
            steps.put("STEP010", 0);
            Files.write(datasets.resolve(HLQ + "ACCTDATA.XFER" + GEN),
                    concat(engine.accounts().stream().map(Account::toBytes).toList()));
            Files.write(datasets.resolve(HLQ + "XFER.FEES" + GEN),
                    concat(posted.stream().map(TransferOutcome::feeRecord).toList()));
            BigDecimal total = posted.stream().map(TransferOutcome::fee)
                    .reduce(new BigDecimal("0.00"), BigDecimal::add);
            Files.write(sysout.resolve("STEP020.txt"), List.of(
                    String.format("XFERFEE: TRANSFERS POSTED %09d", posted.size()),
                    "XFERFEE: TOTAL FEES " + signedDisplay(total)));
            steps.put("STEP020", 0);
            ledger = posted.stream().map(TransferOutcome::ledger).toList();

            Files.write(datasets.resolve(HLQ + "XFER.RECON.RPT" + GEN), ReconReport.lines(posted));
            if (posted.isEmpty()) {
                Files.write(sysout.resolve("STEP030.txt"), List.of("CBXFR03C: NO FEE RECORDS"));
                steps.put("STEP030", 4);
            } else {
                Files.write(sysout.resolve("STEP030.txt"),
                        List.of("CBXFR03C: GRAND TOTAL FEE " + signedDisplay(total)));
                steps.put("STEP030", 0);
            }
        }

        Files.write(db2.resolve("CTL_XFER_PARM.csv"), inputs.rules().csvLines());
        List<String> ledgerCsv = new ArrayList<>();
        ledgerCsv.add("tran_id,tran_dt,src_acct_id,tgt_acct_id,book_id,tran_amt,fee_amt,cap_applied");
        ledger.stream().sorted(Comparator.comparing(LedgerRow::tranId))
                .forEach(row -> ledgerCsv.add(row.toCsv()));
        Files.write(db2.resolve("XFER_FEE_LEDGER.csv"), ledgerCsv);

        int maxcc = steps.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        String stepsJson = steps.entrySet().stream()
                .map(e -> "    \"" + e.getKey() + "\": " + e.getValue())
                .collect(Collectors.joining(",\n"));
        Files.writeString(out.resolve("rc.json"),
                "{\n  \"steps\": {\n" + stepsJson + "\n  },\n  \"maxcc\": " + maxcc + "\n}\n");
    }

    /** DISPLAY of a PIC S9(09)V99 COMP-3 item under GnuCOBOL: leading sign, 11 digits. */
    static String signedDisplay(BigDecimal value) {
        String digits = value.abs().setScale(2).unscaledValue().toString();
        return (value.signum() < 0 ? "-" : "+") + "0".repeat(Math.max(0, 11 - digits.length())) + digits;
    }

    private static byte[] concat(List<byte[]> records) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        records.forEach(buffer::writeBytes);
        return buffer.toByteArray();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
