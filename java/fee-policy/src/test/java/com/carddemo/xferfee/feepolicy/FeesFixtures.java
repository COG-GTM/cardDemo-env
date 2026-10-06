package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeeRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the COBOL-recorded {@code XFER.FEES} datasets and rule tables from {@code fixtures/xferfee}. */
final class FeesFixtures {

    record Field(String name, int offset, int length, boolean text, boolean packed, int scale) {}

    record FeeRow(
            String caseName,
            String tranId,
            String bookId,
            BigDecimal amount,
            BigDecimal feePct,
            BigDecimal feeAmount,
            String capApplied,
            LocalDate ruleEffDate) {

        @Override
        public String toString() {
            return caseName + "/" + tranId;
        }
    }

    private static final Pattern PIC = Pattern.compile(
            "^\\s*\\d+\\s+([A-Z0-9-]+)\\s+PIC\\s+([SX0-9V()]+)(?:\\s+(COMP-3|COMP3))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEAT = Pattern.compile("([X9])(?:\\((\\d+)\\))?");
    private static final String FEES_DSN = "AWS.M2.CARDDEMO.XFER.FEES";

    private FeesFixtures() {}

    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("fixtures/xferfee")) && Files.isDirectory(dir.resolve("copybook"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixtures/xferfee not found above " + Path.of("").toAbsolutePath());
    }

    static List<String> cases() {
        try (Stream<Path> dirs = Files.list(repoRoot().resolve("fixtures/xferfee"))) {
            return dirs.filter(dir -> Files.isRegularFile(dir.resolve("case.json")))
                    .map(dir -> dir.getFileName().toString())
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
        Path root = repoRoot();
        List<Field> layout = layout(root.resolve("copybook/CVXFR02Y.cpy"));
        int length = layout.get(layout.size() - 1).offset() + layout.get(layout.size() - 1).length();
        byte[] data = read(feesDataset(root.resolve("fixtures/xferfee").resolve(caseName).resolve("expected/datasets")));
        List<FeeRow> rows = new ArrayList<>();
        for (int at = 0; at + length <= data.length; at += length) {
            String tranId = (String) decode(data, at, field(layout, "XFE-TRAN-ID"));
            rows.add(new FeeRow(
                    caseName,
                    tranId,
                    (String) decode(data, at, field(layout, "XFE-BOOK-ID")),
                    (BigDecimal) decode(data, at, field(layout, "XFE-TRAN-AMT")),
                    (BigDecimal) decode(data, at, field(layout, "XFE-FEE-PCT")),
                    (BigDecimal) decode(data, at, field(layout, "XFE-FEE-AMT")),
                    (String) decode(data, at, field(layout, "XFE-CAP-APPLIED")),
                    LocalDate.parse((String) decode(data, at, field(layout, "XFE-RULE-EFF-DT")))));
        }
        return rows;
    }

    /** {@code db2_before/CTL_XFER_PARM.csv}: book_id,fee_pct,fee_cap,eff_dt,exp_dt. */
    static List<FeeRule> rules(String caseName) {
        Path csv = repoRoot().resolve("fixtures/xferfee").resolve(caseName).resolve("db2_before/CTL_XFER_PARM.csv");
        try {
            return Files.readAllLines(csv).stream()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1))
                    .map(c -> new FeeRule(c[0], new BigDecimal(c[1].strip()), new BigDecimal(c[2].strip()),
                            LocalDate.parse(c[3].strip()), LocalDate.parse(c[4].strip())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The rule the COBOL run recorded for this row (book + recorded effective date). */
    static FeeRule recordedRule(FeeRow row) {
        return rules(row.caseName()).stream()
                .filter(rule -> rule.bookId().strip().equals(row.bookId().strip()))
                .filter(rule -> rule.effectiveDate().equals(row.ruleEffDate()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no CTL_XFER_PARM row for " + row));
    }

    private static Path feesDataset(Path datasets) {
        try (Stream<Path> files = Files.list(datasets)) {
            return files.filter(p -> p.getFileName().toString().matches(Pattern.quote(FEES_DSN) + "(\\.G\\d{4}V00)?"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IllegalStateException(FEES_DSN + " missing in " + datasets));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Field> layout(Path copybook) {
        List<Field> fields = new ArrayList<>();
        int offset = 0;
        for (String line : readLines(copybook)) {
            Matcher m = PIC.matcher(line);
            if (!m.find()) {
                continue;
            }
            String pic = m.group(2).toUpperCase(Locale.ROOT);
            String body = pic.startsWith("S") ? pic.substring(1) : pic;
            int v = body.indexOf('V');
            int intDigits = count(v < 0 ? body : body.substring(0, v));
            int scale = v < 0 ? 0 : count(body.substring(v + 1));
            boolean text = body.contains("X");
            boolean packed = !text && m.group(3) != null;
            int digits = intDigits + scale;
            int length = text ? count(body) : packed ? (digits + 2) / 2 : digits;
            fields.add(new Field(m.group(1).toUpperCase(Locale.ROOT), offset, length, text, packed, scale));
            offset += length;
        }
        return fields;
    }

    private static int count(String piece) {
        int total = 0;
        Matcher m = REPEAT.matcher(piece);
        while (m.find()) {
            total += m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        }
        return total;
    }

    private static Field field(List<Field> layout, String name) {
        return layout.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static Object decode(byte[] data, int record, Field f) {
        int start = record + f.offset();
        if (f.text()) {
            return new String(data, start, f.length(), StandardCharsets.US_ASCII).stripTrailing();
        }
        if (!f.packed()) {
            throw new IllegalArgumentException("zoned decimal not needed for CVXFR02Y: " + f.name());
        }
        StringBuilder digits = new StringBuilder();
        int sign = 1;
        for (int i = 0; i < f.length(); i++) {
            int b = data[start + i] & 0xFF;
            digits.append(b >> 4);
            if (i < f.length() - 1) {
                digits.append(b & 0x0F);
            } else if ((b & 0x0F) == 0x0D) {
                sign = -1;
            }
        }
        BigDecimal value = new BigDecimal(new java.math.BigInteger(digits.toString()), f.scale());
        return sign < 0 ? value.negate() : value;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code XFE-CAP-APPLIED} as recorded: {@code Y}/{@code N}. */
    static String flag(com.carddemo.xferfee.contracts.FeeResult result) {
        return result.capApplied() ? "Y" : "N";
    }
}
