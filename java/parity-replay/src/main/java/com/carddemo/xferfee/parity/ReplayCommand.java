package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.legacy.ingress.IngressBatch;
import com.carddemo.xferfee.legacy.ingress.LegacyFileIngress;
import com.carddemo.xferfee.legacy.ingress.LegacyInputFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ReplayCommand implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayCommand.class);

    private final ChainReplay chain;
    private final ReplayOutputs outputs;
    private final LegacyFileIngress ingress;
    private final ObjectMapper json;

    ReplayCommand(ChainReplay chain, ReplayOutputs outputs, LegacyFileIngress ingress, ObjectMapper json) {
        this.chain = chain;
        this.outputs = outputs;
        this.ingress = ingress;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("case")) {
            return;
        }
        ReplayOptions options = ReplayOptions.from(args);
        if (!Files.exists(options.caseRoot().resolve("case.json"))) {
            throw new IllegalArgumentException("unknown case " + options.caseName() + " under " + options.fixtures());
        }
        ReplayInputs inputs = inputs(options);
        ChainReplay.Result result = chain.run(inputs);
        outputs.writeRaw(options.out(), result);
        if (options.codec() == ReplayOptions.Codec.JAVA) {
            outputs.writeJavaCandidate(options.out(), result, inputs);
        }
        result.status().forEach((step, outcome) ->
                LOG.info("{} [codec={}] {}: {}", options.caseName(), options.codec().name().toLowerCase(), step, outcome));
    }

    ReplayInputs inputs(ReplayOptions options) throws IOException {
        Path db2 = options.caseRoot().resolve("db2_before");
        var rules = Db2Tables.readRules(db2.resolve(Db2Tables.CTL_XFER_PARM));
        var ledger = Db2Tables.readLedger(db2.resolve(Db2Tables.XFER_FEE_LEDGER));
        if (options.codec() == ReplayOptions.Codec.JAVA) {
            IngressBatch batch = ingress.read(LegacyInputFiles.in(options.caseRoot().resolve("input")));
            List<Account> accounts = new ArrayList<>();
            List<CardXref> xrefs = new ArrayList<>();
            ingress.load(batch, accounts::add, xrefs::add);
            return new ReplayInputs(batch.transactions(), xrefs, accounts, rules, ledger, batch);
        }
        Path in = options.out().resolve("input");
        return new ReplayInputs(
                jsonLines(in.resolve("DALYTRAN.jsonl"), DailyTransaction.class),
                jsonLines(in.resolve("CARDXREF.jsonl"), CardXref.class),
                jsonLines(in.resolve("ACCTDATA.jsonl"), Account.class),
                rules, ledger, null);
    }

    private <T> List<T> jsonLines(Path file, Class<T> type) throws IOException {
        List<T> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(json.readValue(line, type));
            }
        }
        return rows;
    }
}
