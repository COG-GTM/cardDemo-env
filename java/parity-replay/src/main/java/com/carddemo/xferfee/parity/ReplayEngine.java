package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.AccountPosting.PostingResult;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.Reconciliation.ReconResult;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferIntake.IntakeResult;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process equivalent of the {@code XFERFEEP} proc: STEP010 intake, STEP020 posting, STEP030
 * reconciliation. Step sequencing mirrors {@code tools/runjcl/runjcl.py}, which recorded the
 * expected outputs: the job stops at the first step with a non-zero RC and that step's datasets
 * are not cataloged. Steps whose module is not implemented yet are skipped and write nothing.
 */
public class ReplayEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayEngine.class);

    private final Optional<FeeSchedule> feeSchedule;
    private final Optional<TransferIntake> intake;
    private final Optional<AccountPosting> posting;
    private final Optional<Reconciliation> reconciliation;

    public ReplayEngine(
            Optional<FeeSchedule> feeSchedule,
            Optional<TransferIntake> intake,
            Optional<AccountPosting> posting,
            Optional<Reconciliation> reconciliation) {
        this.feeSchedule = feeSchedule;
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
    }

    public Map<String, Integer> run(ReplayOptions options) throws IOException {
        Fixture fixture = Fixture.load(options);
        CandidateWriter writer = new CandidateWriter(options.out());
        writer.reset();
        Map<String, Integer> steps = new LinkedHashMap<>();

        feeSchedule.ifPresentOrElse(
                schedule -> schedule.seed(fixture.feeRules()),
                () -> LOG.warn("FeeSchedule not implemented: CTL_XFER_PARM not written"));

        boolean stopped = false;
        List<TransferRequested> requested = List.of();
        if (intake.isPresent()) {
            IntakeResult result = intake.get().extract(
                    fixture.dailyTransactions(), fixture.cardXrefs(), fixture.accounts());
            stopped = !record(steps, writer, result.report());
            if (!stopped) {
                requested = result.requested();
                writer.dataset(LegacyRecords.EXTRACT_DSN,
                        requested.stream().map(LegacyRecords::extractRow).toList());
            }
        } else {
            requested = options.stubUpstream() ? fixture.recordedExtract() : List.of();
            LOG.warn("STEP010 TransferIntake not implemented: skipped{}",
                    options.stubUpstream() ? " (STEP020 fed from recorded XFER.EXTRACT)" : "");
        }

        List<TransferPosted> posted = List.of();
        if (stopped) {
            posting.ifPresent(p -> LOG.info("STEP020 not run: job stopped at {}", lastStep(steps)));
        } else if (posting.isPresent()) {
            PostingResult result = posting.get().post(
                    requested, fixture.accounts(), new ArrayList<>(fixture.ledgerBefore()));
            stopped = !record(steps, writer, result.report());
            if (!stopped) {
                posted = result.posted();
                writer.dataset(LegacyRecords.ACCTDATA_XFER_DSN,
                        result.accountMasterAfter().stream().map(LegacyRecords::accountRow).toList());
                writer.dataset(LegacyRecords.FEES_DSN,
                        posted.stream().map(LegacyRecords::feeRow).toList());
                writer.ledger(result.ledgerAfter());
            }
        } else {
            posted = options.stubUpstream() ? fixture.recordedFees() : List.of();
            LOG.warn("STEP020 AccountPosting not implemented: skipped{}",
                    options.stubUpstream() ? " (STEP030 fed from recorded XFER.FEES)" : "");
        }
        if (stopped && posting.isPresent()) {
            writer.ledger(fixture.ledgerBefore());
        }

        if (feeSchedule.isPresent()) {
            writer.feeRules(feeSchedule.get().rules());
        }

        if (stopped) {
            reconciliation.ifPresent(r -> LOG.info("STEP030 not run: job stopped at {}", lastStep(steps)));
        } else if (reconciliation.isPresent()) {
            ReconResult result = reconciliation.get().reconcile(posted);
            if (record(steps, writer, result.report())) {
                writer.dataset(LegacyRecords.RECON_DSN,
                        result.reportLines().stream().map(LegacyRecords::textRow).toList());
            }
        } else {
            LOG.warn("STEP030 Reconciliation not implemented: skipped");
        }

        writer.rc(steps);
        LOG.info("replay {} -> {} steps={}", options.caseName(), options.out(), steps);
        return steps;
    }

    /** Records the step's RC and SYSOUT; returns whether the job continues (RC 0). */
    private static boolean record(Map<String, Integer> steps, CandidateWriter writer, StepReport report)
            throws IOException {
        steps.put(report.step(), report.returnCode());
        writer.sysout(report);
        return report.returnCode() == 0;
    }

    private static String lastStep(Map<String, Integer> steps) {
        return steps.keySet().stream().reduce((first, second) -> second).orElse("?");
    }
}
