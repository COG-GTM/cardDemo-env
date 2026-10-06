package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.legacy.egress.LegacyEgress;
import com.carddemo.xferfee.legacy.io.GenerationDataGroup;
import com.carddemo.xferfee.legacy.record.LegacyCopybooks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Writes {@code <out>/out/} (raw Java output: {@code datasets/<DSN>.jsonl}, {@code db2_after/},
 * {@code sysout/}, {@code rc.json}) and, for {@code --codec=java}, {@code <out>/candidate/}.
 */
@Component
class ReplayOutputs {

    static final String RAW = "out";
    static final String CANDIDATE = "candidate";

    private final ObjectMapper json;
    private final LegacyEgress egress;

    ReplayOutputs(ObjectMapper json, LegacyEgress egress) {
        this.json = json.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.egress = egress;
    }

    void writeRaw(Path out, ChainReplay.Result result) throws IOException {
        Path raw = out.resolve(RAW);
        Path datasets = raw.resolve("datasets");
        Files.createDirectories(datasets);
        if (result.intake() != null) {
            jsonLines(datasets.resolve(LegacyCopybooks.EXTRACT_DSN + ".jsonl"), result.intake().requested());
        }
        // BR-15: an abended STEP020 deletes its (+1) generations
        if (result.postingCommitted()) {
            jsonLines(datasets.resolve(LegacyCopybooks.FEES_DSN + ".jsonl"), result.posting().posted());
            jsonLines(datasets.resolve(LegacyCopybooks.ACCTDATA_XFER_DSN + ".jsonl"), result.posting().accountMasterAfter());
        }
        if (result.recon() != null) {
            Files.write(datasets.resolve(LegacyCopybooks.RECON_DSN + ".txt"), result.recon().reportLines(), StandardCharsets.UTF_8);
        }
        Db2Tables.writeRules(raw.resolve("db2_after").resolve(Db2Tables.CTL_XFER_PARM), result.rulesAfter());
        Db2Tables.writeLedger(raw.resolve("db2_after").resolve(Db2Tables.XFER_FEE_LEDGER), result.ledgerAfter());
        Path sysout = raw.resolve("sysout");
        Files.createDirectories(sysout);
        Map<String, Integer> codes = new LinkedHashMap<>();
        for (StepReport step : result.steps()) {
            Files.write(sysout.resolve(step.step() + ".txt"), step.sysout(), StandardCharsets.UTF_8);
            codes.put(step.step(), step.returnCode());
        }
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("steps", codes);
        rc.put("maxcc", codes.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        Files.writeString(raw.resolve("rc.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(rc) + "\n");
        List<Object> rejected = new java.util.ArrayList<>();
        if (result.intake() != null) {
            rejected.addAll(result.intake().rejected());
        }
        if (result.posting() != null) {
            rejected.addAll(result.posting().rejected());
        }
        jsonLines(raw.resolve("rejected.jsonl"), rejected);
        Files.writeString(raw.resolve("replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result.status()) + "\n");
    }

    /** {@code --codec=java}: the candidate's datasets are encoded by the legacy-adapter egress. */
    void writeJavaCandidate(Path out, ChainReplay.Result result, ReplayInputs in) throws IOException {
        Path candidate = out.resolve(CANDIDATE);
        Path datasets = candidate.resolve("datasets");
        Files.createDirectories(datasets);
        if (result.intake() != null) {
            egress.writeGeneration(datasets, LegacyCopybooks.EXTRACT_DSN,
                    egress.extractRecords(result.intake().requested(), in.batch()));
        }
        if (result.postingCommitted()) {
            egress.writeAccountMasterGeneration(datasets, in.batch().accounts(),
                    result.posting().accountMasterAfter(), result.posting().posted());
            egress.writeGeneration(datasets, LegacyCopybooks.FEES_DSN, egress.feeRecords(result.posting().posted()));
        }
        if (result.recon() != null) {
            byte[] report = egress.reportBytes(result.recon().reportLines());
            new GenerationDataGroup(datasets, LegacyCopybooks.RECON_DSN).writeNext(stream -> stream.write(report));
        }
        Path raw = out.resolve(RAW);
        copyTree(raw.resolve("db2_after"), candidate.resolve("db2_after"));
        copyTree(raw.resolve("sysout"), candidate.resolve("sysout"));
        Files.copy(raw.resolve("rc.json"), candidate.resolve("rc.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    private void jsonLines(Path file, List<?> rows) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Object row : rows) {
            text.append(json.writeValueAsString(row)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : files.toList()) {
                Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
