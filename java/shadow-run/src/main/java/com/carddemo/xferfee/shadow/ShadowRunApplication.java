package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.DailyTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java leg of the shadow run.
 *
 * <pre>
 *   --case &lt;name&gt; --out &lt;dir&gt; [--fixtures fixtures/xferfee]                  replay a recorded fixture case
 *   --input-dir &lt;dir&gt; --rules &lt;csv&gt; [--ledger &lt;csv&gt;] --out &lt;dir&gt;   replay a staged day
 * </pre>
 *
 * The input dir holds DALYTRAN.PS, CARDXREF.PS and ACCTDATA.PS; rules is a CTL_XFER_PARM CSV snapshot and ledger
 * the XFER_FEE_LEDGER rows present before the run. Exit code is the chain's MAXCC.
 */
@SpringBootApplication
public class ShadowRunApplication implements ApplicationRunner, ExitCodeGenerator {

    private int exitCode;

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ShadowRunApplication.class, args)));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path out = Path.of(required(args, "out"));
        Path input;
        Path rules;
        Path ledger;
        if (option(args, "case", null) != null) {
            Path caseDir = Path.of(option(args, "fixtures", "fixtures/xferfee")).resolve(required(args, "case"));
            input = caseDir.resolve("input");
            rules = caseDir.resolve("db2_before/CTL_XFER_PARM.csv");
            ledger = caseDir.resolve("db2_before/XFER_FEE_LEDGER.csv");
        } else {
            input = Path.of(required(args, "input-dir"));
            rules = Path.of(required(args, "rules"));
            ledger = Path.of(option(args, "ledger", input.resolve("XFER_FEE_LEDGER.csv").toString()));
        }
        Files.createDirectories(out);
        ChainResult result = replay(input, rules, ledger, out);
        exitCode = result.maxcc();
        System.out.printf("shadow-run: steps %s maxcc %d -> %s%n", result.stepRc(), exitCode, out);
    }

    /** Replays one staged day and writes the candidate to {@code out}. */
    public static ChainResult replay(Path input, Path rules, Path ledger, Path out) throws IOException {
        Path master = input.resolve("ACCTDATA.PS");
        Path daily = input.resolve("DALYTRAN.PS");
        List<DailyTransaction> transactions = LegacyInputs.transactions(daily);
        ChainResult result = ShadowChain.legacy(LegacyAmounts.fromRaw(transactions, LegacyInputs.rawAmounts(daily)))
                .run(transactions,
                LegacyInputs.xrefs(input.resolve("CARDXREF.PS")),
                LegacyInputs.accounts(master),
                Snapshots.rules(rules),
                Snapshots.ledger(ledger));
        List<String> raw = LegacyInputs.rawAccounts(master);
        CandidateWriter.write(result, raw, out);
        return result;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static String required(ApplicationArguments args, String name) {
        String value = option(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    /** Accepts both {@code --name=value} and {@code --name value}. */
    static String option(ApplicationArguments args, String name, String fallback) {
        String[] raw = args.getSourceArgs();
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].startsWith("--" + name + "=")) {
                return raw[i].substring(name.length() + 3);
            }
            if (raw[i].equals("--" + name) && i + 1 < raw.length && !raw[i + 1].startsWith("--")) {
                return raw[i + 1];
            }
        }
        return fallback;
    }
}
