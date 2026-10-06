package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.events.RunMode;
import com.carddemo.xferfee.posting.PerTransferPosting;
import java.util.ArrayList;
import java.util.List;

/** XFERFEEP run in one JVM through the contract beans (no Kafka, no database). */
final class InProcessChain {

    private final TransferIntake intake;
    private final AccountPosting posting;
    private final Reconciliation reconciliation;
    private final FeeSchedule feeSchedule;

    InProcessChain(TransferIntake intake, AccountPosting posting, Reconciliation reconciliation,
            FeeSchedule feeSchedule) {
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
        this.feeSchedule = feeSchedule;
    }

    ChainOutput run(CaseInput input, RunMode mode) {
        feeSchedule.seed(input.feeRules());
        List<StepReport> steps = new ArrayList<>();

        TransferIntake.IntakeResult extracted =
                intake.extract(input.dailyTransactions(), input.cardXrefs(), input.accounts());
        steps.add(extracted.report());
        if (!ChainOutput.kept(extracted.report())) {
            return new ChainOutput(steps, null, null, null, null, feeSchedule.rules(), input.ledgerBefore());
        }

        AccountPosting step020 = mode == RunMode.PER_TRANSFER ? new PerTransferPosting(posting) : posting;
        AccountPosting.PostingResult posted =
                step020.post(extracted.requested(), input.accounts(), input.ledgerBefore());
        steps.add(posted.report());
        boolean postingKept = ChainOutput.kept(posted.report());

        List<String> report = null;
        if (!ChainOutput.reconBypassed(posted.report())) {
            Reconciliation.ReconResult recon = reconciliation.reconcile(posted.posted());
            steps.add(recon.report());
            report = ChainOutput.kept(recon.report()) ? recon.reportLines() : null;
        }
        return new ChainOutput(
                steps,
                extracted.requested(),
                postingKept ? posted.accountMasterAfter() : null,
                postingKept ? posted.posted() : null,
                report,
                feeSchedule.rules(),
                postingKept ? posted.ledgerAfter() : input.ledgerBefore());
    }
}
