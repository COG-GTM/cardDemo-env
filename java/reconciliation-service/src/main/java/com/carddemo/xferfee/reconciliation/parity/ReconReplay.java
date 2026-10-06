package com.carddemo.xferfee.reconciliation.parity;

import com.carddemo.xferfee.contracts.ContractJson;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.port.ReconciliationReport;
import com.carddemo.xferfee.reconciliation.LegacyReconStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * STEP030-only replay for {@code tools/parity/java_recon_stage.py}: feeds recorded
 * {@code XFER.FEES} (as {@link TransferPosted} JSON-lines) and the recorded STEP020 RC through
 * {@link LegacyReconStep}, and writes the same files {@code ChainReplay} would for STEP030.
 *
 * <pre>--fees FILE.jsonl --posting-rc N --out DIR</pre>
 */
public final class ReconReplay {

    private static final ObjectMapper JSON = ContractJson.mapper();

    private ReconReplay() {
    }

    public static Optional<ReconciliationReport> replay(List<TransferPosted> fees, int postingRc, Path out)
            throws IOException {
        Files.createDirectories(out);
        Optional<ReconciliationReport> report = new LegacyReconStep().run(fees, postingRc);
        Map<String, Integer> codes = new LinkedHashMap<>();
        Map<String, String> status = new LinkedHashMap<>();
        if (report.isPresent()) {
            ReconciliationReport r = report.get();
            Files.write(out.resolve("XFER.RECON.RPT.txt"), r.lines(), StandardCharsets.UTF_8);
            Path sysout = Files.createDirectories(out.resolve("sysout"));
            Files.write(sysout.resolve(LegacyReconStep.STEP + ".txt"), r.report().sysout(), StandardCharsets.UTF_8);
            codes.put(LegacyReconStep.STEP, r.report().returnCode());
            status.put(LegacyReconStep.STEP, "RC=" + r.report().returnCode());
        } else {
            status.put(LegacyReconStep.STEP, "BYPASSED: COND=(4,LT,STEP020)");
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("rc.json").toFile(), Map.of("steps", codes));
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("replay.json").toFile(), status);
        return report;
    }

    public static void main(String[] args) throws IOException {
        Path fees = null;
        Path out = null;
        Integer postingRc = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fees" -> fees = Path.of(args[++i]);
                case "--posting-rc" -> postingRc = Integer.parseInt(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> usage("unknown argument " + args[i]);
            }
        }
        if (fees == null || out == null || postingRc == null) {
            usage("--fees, --posting-rc and --out are required");
        }
        Optional<ReconciliationReport> report = replay(read(fees), postingRc, out);
        System.out.println(report
                .map(r -> "STEP030: RC=" + r.report().returnCode() + ", " + r.lines().size() + " report lines")
                .orElse("STEP030: BYPASSED: COND=(4,LT,STEP020), posting RC " + postingRc));
    }

    static List<TransferPosted> read(Path file) throws IOException {
        List<TransferPosted> rows = new ArrayList<>();
        if (!Files.exists(file)) {
            return rows;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(JSON.readValue(line, TransferPosted.class));
            }
        }
        return rows;
    }

    private static void usage(String message) {
        System.err.println("ReconReplay: " + message);
        System.err.println("usage: --fees FILE.jsonl --posting-rc N --out DIR");
        System.exit(2);
    }
}
