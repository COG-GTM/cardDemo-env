package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.StepReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Writes the raw replay output tree consumed by {@code tools/parity/java_candidate.py encode}. */
final class CandidateWriter {

    private final Path out;

    CandidateWriter(Path out) {
        this.out = out;
    }

    void reset() throws IOException {
        if (Files.exists(out)) {
            try (Stream<Path> paths = Files.walk(out)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(out);
    }

    void dataset(String dsn, List<Map<String, String>> rows) throws IOException {
        JsonLines.write(out.resolve("datasets").resolve(dsn + ".jsonl"), rows);
    }

    void feeRules(List<FeeRule> rules) throws IOException {
        Db2Csv.writeFeeRules(out.resolve("db2_after").resolve(Db2Csv.CTL_XFER_PARM + ".csv"), rules);
    }

    void ledger(List<LedgerEntry> ledger) throws IOException {
        Db2Csv.writeLedger(out.resolve("db2_after").resolve(Db2Csv.XFER_FEE_LEDGER + ".csv"), ledger);
    }

    void sysout(StepReport report) throws IOException {
        Path file = out.resolve("sysout").resolve(report.step() + ".txt");
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        report.sysout().forEach(line -> text.append(line).append('\n'));
        Files.writeString(file, text);
    }

    void rc(Map<String, Integer> steps) throws IOException {
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", steps);
        rc.put("maxcc", steps.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(out.resolve("rc.json").toFile(), rc);
    }
}
