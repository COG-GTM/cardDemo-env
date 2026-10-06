package org.carddemo.xferfee.replay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.xferfee.contracts.FeePolicy;
import org.carddemo.xferfee.contracts.FeeResult;
import org.carddemo.xferfee.contracts.FeeRule;
import org.carddemo.xferfee.fee.CobolFeePolicy;

/**
 * Replays one {@code fixtures/xferfee/<case>} in-process and writes JSON-lines per DSN for
 * {@code tools/parity/java_candidate.py}. Only {@code XFER.FEES} is produced so far; its {@code XFE-FEE-AMT} and
 * {@code XFE-CAP-APPLIED} come from {@link FeePolicy}.
 *
 * <pre>
 * java org.carddemo.xferfee.replay.ParityReplay --case half_cent --out work/parity-java/half_cent/candidate
 * </pre>
 */
public final class ParityReplay {

    static final String FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES";
    static final int RC_NO_FEE_RULE = 8;

    private static final int DALYTRAN_LENGTH = 350;
    private static final int CARDXREF_LENGTH = 50;
    private static final int ACCTDATA_LENGTH = 300;

    record Transfer(String tranId, LocalDate tranDt, long srcAcctId, long tgtAcctId, String bookId,
                    BigDecimal tranAmt) {
    }

    static final class NoFeeRuleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoFeeRuleException(String bookId) {
            super("XFERFEE: NO FEE RULE FOR BOOK " + bookId);
        }
    }

    private final FeePolicy feePolicy;

    ParityReplay(FeePolicy feePolicy) {
        this.feePolicy = feePolicy;
    }

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        Path fixtures = Path.of(options.getOrDefault("--fixtures", "fixtures/xferfee"));
        Path caseDir = fixtures.resolve(require(options, "--case"));
        Path out = Path.of(require(options, "--out"));
        try {
            new ParityReplay(new CobolFeePolicy()).run(caseDir, out);
        } catch (NoFeeRuleException e) {
            System.err.println(e.getMessage());
            System.exit(RC_NO_FEE_RULE);
        }
    }

    void run(Path caseDir, Path out) {
        List<FeeRule> rules = loadRules(caseDir.resolve("db2_before/CTL_XFER_PARM.csv"));
        List<String> fees = new ArrayList<>();
        for (Transfer transfer : extractTransfers(caseDir.resolve("input"))) {
            fees.add(feeRecord(transfer, resolveRule(rules, transfer)));
        }
        write(out.resolve(FEES_DSN + ".jsonl"), fees);
    }

    String feeRecord(Transfer transfer, FeeRule rule) {
        FeeResult fee = feePolicy.apply(transfer.tranAmt(), rule);
        Map<String, String> record = new LinkedHashMap<>();
        record.put("XFE-TRAN-ID", transfer.tranId());
        record.put("XFE-TRAN-DT", transfer.tranDt().toString());
        record.put("XFE-SRC-ACCT-ID", Long.toString(transfer.srcAcctId()));
        record.put("XFE-TGT-ACCT-ID", Long.toString(transfer.tgtAcctId()));
        record.put("XFE-BOOK-ID", transfer.bookId());
        record.put("XFE-TRAN-AMT", transfer.tranAmt().toPlainString());
        record.put("XFE-FEE-PCT", rule.feePct().toPlainString());
        record.put("XFE-FEE-AMT", fee.feeAmt().toPlainString());
        record.put("XFE-CAP-APPLIED", fee.capApplied());
        record.put("XFE-RULE-EFF-DT", rule.effDt().toString());
        return json(record);
    }

    /** CBXFR01C selection: type-08 DALYTRAN records whose card and source account resolve. */
    static List<Transfer> extractTransfers(Path input) {
        Map<String, Long> cardToAcct = new HashMap<>();
        for (byte[] xref : FixedRecords.read(input.resolve("CARDXREF.PS"), CARDXREF_LENGTH)) {
            cardToAcct.put(FixedRecords.text(xref, 0, 16), FixedRecords.unsigned(xref, 25, 11));
        }
        Map<Long, String> acctToBook = new HashMap<>();
        for (byte[] acct : FixedRecords.read(input.resolve("ACCTDATA.PS"), ACCTDATA_LENGTH)) {
            acctToBook.put(FixedRecords.unsigned(acct, 0, 11), FixedRecords.text(acct, 112, 10).stripTrailing());
        }
        List<Transfer> transfers = new ArrayList<>();
        for (byte[] tran : FixedRecords.read(input.resolve("DALYTRAN.PS"), DALYTRAN_LENGTH)) {
            if (!"08".equals(FixedRecords.text(tran, 16, 2))) {
                continue;
            }
            Long src = cardToAcct.get(FixedRecords.text(tran, 262, 16));
            if (src == null || !acctToBook.containsKey(src)) {
                continue;
            }
            transfers.add(new Transfer(
                    FixedRecords.text(tran, 0, 16).stripTrailing(),
                    LocalDate.parse(FixedRecords.text(tran, 278, 10)),
                    src,
                    FixedRecords.unsigned(tran, 32 + 13, 11),
                    acctToBook.get(src),
                    FixedRecords.zoned(tran, 132, 11, 2)));
        }
        return transfers;
    }

    /** XFERFEE rule lookup: {@code BOOK_ID = ? AND EFF_DT <= TRAN_DT AND EXP_DT > TRAN_DT}. */
    static FeeRule resolveRule(List<FeeRule> rules, Transfer transfer) {
        return rules.stream()
                .filter(rule -> rule.bookId().equals(transfer.bookId()) && rule.covers(transfer.tranDt()))
                .findFirst()
                .orElseThrow(() -> new NoFeeRuleException(transfer.bookId()));
    }

    static List<FeeRule> loadRules(Path csv) {
        List<String> lines = readLines(csv);
        List<FeeRule> rules = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(",", -1);
            rules.add(new FeeRule(f[0].strip(), new BigDecimal(f[1].strip()), new BigDecimal(f[2].strip()),
                    LocalDate.parse(f[3].strip()), LocalDate.parse(f[4].strip())));
        }
        return rules;
    }

    private static String json(Map<String, String> record) {
        StringBuilder sb = new StringBuilder("{");
        record.forEach((key, value) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append('"').append(key).append("\": \"").append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        });
        return sb.append('}').toString();
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, List<String> lines) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("usage: ParityReplay --case <name> --out <dir> [--fixtures <dir>]");
        }
        return value;
    }
}
