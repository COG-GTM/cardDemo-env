package com.carddemo.xferfee.replay;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.port.AccountPosting;
import com.carddemo.xferfee.contracts.port.CardXref;
import com.carddemo.xferfee.contracts.port.DailyTransaction;
import com.carddemo.xferfee.contracts.port.IntakeResult;
import com.carddemo.xferfee.contracts.port.PostingResult;
import com.carddemo.xferfee.contracts.port.Reconciliation;
import com.carddemo.xferfee.contracts.port.ReconciliationReport;
import com.carddemo.xferfee.contracts.port.StepReport;
import com.carddemo.xferfee.contracts.port.TransferIntake;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs XFERFEEP's three steps against whichever stage beans exist.
 *
 * <p>A step with no bean, or whose bean throws, ends the chain: later steps need its output.
 * Whatever did run is written out, so a partial rewrite shows up as parity differences
 * instead of a crash. Output files under {@code --out}:
 * <ul>
 *   <li>{@code XFER.EXTRACT.jsonl}, {@code XFER.FEES.jsonl}, {@code ACCTDATA.XFER.jsonl}</li>
 *   <li>{@code XFER.RECON.RPT.txt}, {@code rejected.jsonl}, {@code sysout/STEPnnn.txt}</li>
 *   <li>{@code rc.json} (completed steps only) and {@code replay.json} (status of every step)</li>
 * </ul>
 */
@Component
public class ChainReplay {

    static final String DALYTRAN = "DALYTRAN.jsonl";
    static final String CARDXREF = "CARDXREF.jsonl";
    static final String ACCTDATA = "ACCTDATA.jsonl";

    private static final Logger LOG = LoggerFactory.getLogger(ChainReplay.class);

    private final ObjectProvider<TransferIntake> intake;
    private final ObjectProvider<AccountPosting> posting;
    private final ObjectProvider<Reconciliation> reconciliation;

    public ChainReplay(ObjectProvider<TransferIntake> intake, ObjectProvider<AccountPosting> posting,
                       ObjectProvider<Reconciliation> reconciliation) {
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
    }

    public Map<String, String> run(ReplayOptions options) {
        Run run = new Run(options.out());
        List<DailyTransaction> transactions = JsonLines.read(options.in().resolve(DALYTRAN), DailyTransaction.class);
        List<CardXref> xrefs = JsonLines.read(options.in().resolve(CARDXREF), CardXref.class);
        List<Account> accounts = JsonLines.read(options.in().resolve(ACCTDATA), Account.class);

        IntakeResult extracted = run.step("STEP010", TransferIntake.class, intake.getIfAvailable(),
                stage -> stage.extract(transactions, xrefs, accounts), IntakeResult::report);
        if (extracted != null) {
            JsonLines.write(options.out().resolve("XFER.EXTRACT.jsonl"), extracted.selected());
            run.rejected.addAll(extracted.rejected());
        }

        PostingResult posted = extracted == null ? null
                : run.step("STEP020", AccountPosting.class, posting.getIfAvailable(),
                        stage -> stage.post(extracted.selected(), accounts), PostingResult::report);
        if (posted != null) {
            JsonLines.write(options.out().resolve("XFER.FEES.jsonl"), posted.posted());
            JsonLines.write(options.out().resolve("ACCTDATA.XFER.jsonl"), posted.accounts());
            run.rejected.addAll(posted.rejected());
        }

        // STEP030 EXEC PGM=CBXFR03C,COND=(4,LT,STEP020): bypassed when STEP020 RC > 4.
        if (posted != null && posted.report().returnCode() > 4) {
            run.status.put("STEP030", "BYPASSED: COND=(4,LT,STEP020)");
        } else {
            ReconciliationReport report = posted == null ? null
                    : run.step("STEP030", Reconciliation.class, reconciliation.getIfAvailable(),
                            stage -> stage.reconcile(posted.posted()), ReconciliationReport::report);
            if (report != null) {
                JsonLines.writeLines(options.out().resolve("XFER.RECON.RPT.txt"), report.lines());
            }
        }

        run.finish();
        return run.status;
    }

    private static final class Run {
        private final Path out;
        private final Map<String, Integer> codes = new LinkedHashMap<>();
        private final Map<String, String> status = new LinkedHashMap<>();
        private final List<TransferRejected> rejected = new ArrayList<>();
        private boolean stopped;

        Run(Path out) {
            this.out = out;
        }

        <S, R> R step(String step, Class<S> port, S stage, java.util.function.Function<S, R> call,
                      java.util.function.Function<R, StepReport> report) {
            if (stopped) {
                status.put(step, "NOT RUN: earlier step did not complete");
                return null;
            }
            if (stage == null) {
                return stop(step, () -> "NOT IMPLEMENTED: no " + port.getSimpleName() + " bean");
            }
            R result;
            try {
                result = call.apply(stage);
            } catch (RuntimeException e) {
                LOG.error("{} failed in {}", step, stage.getClass().getName(), e);
                return stop(step, () -> "FAILED: " + e);
            }
            StepReport stepReport = report.apply(result);
            codes.put(step, stepReport.returnCode());
            status.put(step, "RC=" + stepReport.returnCode());
            JsonLines.writeLines(out.resolve("sysout").resolve(step + ".txt"), stepReport.sysout());
            return result;
        }

        private <R> R stop(String step, Supplier<String> why) {
            stopped = true;
            status.put(step, why.get());
            LOG.warn("{} {}", step, status.get(step));
            return null;
        }

        void finish() {
            for (String step : List.of("STEP010", "STEP020", "STEP030")) {
                status.putIfAbsent(step, "NOT RUN: earlier step did not complete");
            }
            JsonLines.write(out.resolve("rejected.jsonl"), rejected);
            int maxcc = codes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            JsonLines.writeJson(out.resolve("rc.json"), Map.of("steps", codes, "maxcc", maxcc));
            JsonLines.writeJson(out.resolve("replay.json"), status);
        }
    }
}
