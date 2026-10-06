package com.carddemo.xfer.parity;

import com.carddemo.xfer.contracts.TransferRequested;
import com.carddemo.xfer.intake.IntakeResult;
import com.carddemo.xfer.intake.TransferIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --case <name> --out <dir> [--fixtures <dir>]}: loads {@code <fixtures>/<case>/input}, runs
 * the implemented modules in-process and writes {@code jsonl/<DSN>.jsonl}, {@code sysout/<STEP>.txt}
 * and {@code rc.json} under {@code <out>}. JSON-lines keys are copybook field names so
 * {@code tools/parity/java_candidate.py} can encode them unchanged.
 */
@Component
public class ParityReplayRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String EXTRACT_DSN = "AWS.M2.CARDDEMO.XFER.EXTRACT";

    private final TransferIntakeService intake;
    private final ObjectMapper json = new ObjectMapper();
    private int exitCode;

    public ParityReplayRunner(TransferIntakeService intake) {
        this.intake = intake;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Map<String, String> options = parse(args.getSourceArgs());
        if (!options.containsKey("case") || !options.containsKey("out")) {
            System.err.println("usage: parity-replay --case <name> --out <dir> [--fixtures <dir>]");
            exitCode = 2;
            return;
        }
        Path fixtures = Path.of(options.getOrDefault("fixtures", "fixtures/xferfee"));
        replay(fixtures.resolve(options.get("case")).resolve("input"), Path.of(options.get("out")));
    }

    void replay(Path input, Path out) throws IOException {
        IntakeResult step010 = intake.extract(
                LegacyDatasets.dailyTransactions(input.resolve("DALYTRAN.PS")),
                LegacyDatasets.cardCrossReferences(input.resolve("CARDXREF.PS")),
                LegacyDatasets.accounts(input.resolve("ACCTDATA.PS")));

        writeJsonLines(out.resolve("jsonl").resolve(EXTRACT_DSN + ".jsonl"),
                step010.requested().stream().map(ParityReplayRunner::extractRecord).toList());
        writeLines(out.resolve("sysout").resolve("STEP010.txt"), step010.sysout());

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("STEP010", step010.returnCode());
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", steps);
        rc.put("maxcc", step010.returnCode());
        Files.writeString(out.resolve("rc.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(rc) + "\n");
    }

    /** {@link TransferRequested} as an XFER-EXTRACT-RECORD (CVXFR01Y). */
    static Map<String, String> extractRecord(TransferRequested transfer) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("XFR-TRAN-ID", transfer.tranId());
        record.put("XFR-TRAN-DT", transfer.tranDate());
        record.put("XFR-SRC-ACCT-ID", transfer.sourceAccountId());
        record.put("XFR-TGT-ACCT-ID", transfer.targetAccountId());
        record.put("XFR-BOOK-ID", transfer.bookId());
        record.put("XFR-TRAN-AMT", transfer.amount().toPlainString());
        record.put("XFR-CARD-NUM", transfer.cardNumber());
        return record;
    }

    private void writeJsonLines(Path file, List<Map<String, String>> records) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (Map<String, String> record : records) {
            text.append(json.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, text, StandardCharsets.US_ASCII);
    }

    private static void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq > 0) {
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            } else if (i + 1 < args.length) {
                options.put(arg.substring(2), args[++i]);
            }
        }
        return options;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
