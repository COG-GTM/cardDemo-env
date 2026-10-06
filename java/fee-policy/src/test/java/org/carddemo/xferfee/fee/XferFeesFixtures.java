package org.carddemo.xferfee.fee;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.carddemo.xferfee.contracts.FeeRule;

/** Reads the COBOL-recorded {@code XFER.FEES} datasets and {@code CTL_XFER_PARM} seeds from {@code fixtures/xferfee}. */
final class XferFeesFixtures {

    static final String FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES.G0001V00";
    static final int CVXFR02Y_LENGTH = 100;

    /** One decoded CVXFR02Y record plus the rule XFERFEE resolved for it. */
    record FeeRow(String caseName, String tranId, LocalDate tranDt, String bookId, BigDecimal tranAmt,
                  BigDecimal feePct, BigDecimal feeAmt, String capApplied, LocalDate ruleEffDt, FeeRule rule) {

        @Override
        public String toString() {
            return caseName + "/" + tranId + " " + bookId + " amt=" + tranAmt.toPlainString() + " pct="
                    + feePct.toPlainString() + " -> fee=" + feeAmt.toPlainString() + " cap=" + capApplied;
        }
    }

    private XferFeesFixtures() {
    }

    static Path root() {
        String configured = System.getProperty("xferfee.fixtures");
        Path path = configured != null ? Path.of(configured) : Path.of("../../fixtures/xferfee");
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("fixtures/xferfee not found at " + path.toAbsolutePath());
        }
        return path.normalize();
    }

    static List<String> cases() {
        try (Stream<Path> children = Files.list(root())) {
            return children.filter(p -> Files.isRegularFile(p.resolve("expected/datasets").resolve(FEES_DSN)))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<FeeRow> allRows() {
        return cases().stream().flatMap(c -> rows(c).stream()).toList();
    }

    static List<FeeRow> rows(String caseName) {
        Path caseDir = root().resolve(caseName);
        List<FeeRule> rules = rules(caseDir.resolve("db2_before/CTL_XFER_PARM.csv"));
        byte[] data = read(caseDir.resolve("expected/datasets").resolve(FEES_DSN));
        if (data.length % CVXFR02Y_LENGTH != 0) {
            throw new IllegalStateException(caseName + ": XFER.FEES is not a multiple of 100 bytes");
        }
        List<FeeRow> rows = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += CVXFR02Y_LENGTH) {
            rows.add(decode(caseName, data, offset, rules));
        }
        return rows;
    }

    /**
     * CVXFR02Y: TRAN-ID X(16) | TRAN-DT X(10) | SRC 9(11) | TGT 9(11) | BOOK X(10) | TRAN-AMT S9(09)V99 COMP-3 (6) |
     * FEE-PCT S9(1)V9(6) COMP-3 (4) | FEE-AMT S9(09)V99 COMP-3 (6) | CAP X(1) | RULE-EFF-DT X(10) | FILLER X(15).
     */
    private static FeeRow decode(String caseName, byte[] data, int base, List<FeeRule> rules) {
        String tranId = text(data, base, 16);
        LocalDate tranDt = LocalDate.parse(text(data, base + 16, 10));
        String bookId = text(data, base + 48, 10);
        BigDecimal tranAmt = packed(data, base + 58, 6, 2);
        BigDecimal feePct = packed(data, base + 64, 4, 6);
        BigDecimal feeAmt = packed(data, base + 68, 6, 2);
        String cap = text(data, base + 74, 1);
        LocalDate ruleEffDt = LocalDate.parse(text(data, base + 75, 10));
        FeeRule rule = rules.stream()
                .filter(r -> r.bookId().equals(bookId) && r.covers(tranDt))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(caseName + "/" + tranId + ": no CTL_XFER_PARM rule"));
        return new FeeRow(caseName, tranId, tranDt, bookId, tranAmt, feePct, feeAmt, cap, ruleEffDt, rule);
    }

    static List<FeeRule> rules(Path csv) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines.stream().skip(1).filter(l -> !l.isBlank()).map(l -> {
            String[] f = l.split(",", -1);
            return new FeeRule(f[0].strip(), new BigDecimal(f[1].strip()), new BigDecimal(f[2].strip()),
                    LocalDate.parse(f[3].strip()), LocalDate.parse(f[4].strip()));
        }).toList();
    }

    private static String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII).strip();
    }

    private static BigDecimal packed(byte[] data, int offset, int length, int scale) {
        StringBuilder digits = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            digits.append(b >> 4);
            if (i < length - 1) {
                digits.append(b & 0x0F);
            }
        }
        int sign = data[offset + length - 1] & 0x0F;
        BigDecimal value = new BigDecimal(new java.math.BigInteger(digits.toString()), scale);
        return sign == 0x0D ? value.negate() : value;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
