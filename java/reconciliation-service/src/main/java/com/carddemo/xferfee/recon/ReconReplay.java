package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.TransferPosted;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Batch entry point for the parity harness: runs STEP030 against an
 * {@code XFER.FEES} dataset and writes a candidate directory laid out like
 * {@code fixtures/xferfee/<case>/expected}.
 *
 * <pre>replay --fees FILE --business-date YYYY-MM-DD --posting-rc N --out DIR</pre>
 */
public final class ReconReplay {

    static final String REPORT_DSN = "AWS.M2.CARDDEMO.XFER.RECON.RPT";

    private ReconReplay() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    public static int run(String[] args) throws IOException {
        Path fees = null;
        Path out = null;
        LocalDate businessDate = LocalDate.now();
        int postingRc = 0;
        for (int index = 0; index < args.length; index++) {
            String value = index + 1 < args.length ? args[index + 1] : null;
            switch (args[index]) {
                case "--fees" -> fees = Path.of(require(value, "--fees"));
                case "--out" -> out = Path.of(require(value, "--out"));
                case "--business-date" -> businessDate = LocalDate.parse(require(value, "--business-date"));
                case "--posting-rc" -> postingRc = Integer.parseInt(require(value, "--posting-rc"));
                default -> throw new IllegalArgumentException("unknown argument: " + args[index]);
            }
            index++;
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        List<TransferPosted> posted = fees != null && Files.exists(fees)
                ? new XferFeesDatasetReader().read(fees, businessDate)
                : List.of();
        Optional<LegacyReconReport> report = new LegacyReconStep().run(posted, postingRc);
        write(out, postingRc, report);
        System.out.println(report
                .map(value -> "STEP030 RC " + value.returnCode() + ", " + posted.size() + " fee records")
                .orElse("STEP030 bypassed: COND=(4,LT,STEP020), STEP020 RC " + postingRc));
        return 0;
    }

    static void write(Path out, int postingRc, Optional<LegacyReconReport> report) throws IOException {
        Files.createDirectories(out.resolve("datasets"));
        Files.createDirectories(out.resolve("sysout"));
        String steps = "    \"STEP020\": " + postingRc;
        int maxcc = postingRc;
        if (report.isPresent()) {
            LegacyReconReport value = report.get();
            Files.writeString(out.resolve("datasets").resolve(REPORT_DSN), value.text(), StandardCharsets.US_ASCII);
            Files.writeString(out.resolve("sysout").resolve("STEP030.txt"), value.sysoutText(),
                    StandardCharsets.US_ASCII);
            steps += ",\n    \"STEP030\": " + value.returnCode();
            maxcc = Math.max(maxcc, value.returnCode());
        }
        Files.writeString(out.resolve("rc.json"),
                "{\n  \"steps\": {\n" + steps + "\n  },\n  \"maxcc\": " + maxcc + "\n}\n");
    }

    private static String require(String value, String flag) {
        if (value == null) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return value;
    }
}
