package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.AccountPosting.PostingResult;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.Reconciliation.ReconResult;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferIntake.IntakeResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** XFERFEEP: STEP010 → STEP020 → STEP030 (COND=(4,LT,STEP020)) over the contract beans. */
@Component
class ChainReplay {

    private final ObjectProvider<FeeSchedule> schedule;
    private final ObjectProvider<TransferIntake> intake;
    private final ObjectProvider<AccountPosting> posting;
    private final ObjectProvider<Reconciliation> reconciliation;

    ChainReplay(ObjectProvider<FeeSchedule> schedule, ObjectProvider<TransferIntake> intake,
            ObjectProvider<AccountPosting> posting, ObjectProvider<Reconciliation> reconciliation) {
        this.schedule = schedule;
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
    }

    record Result(
            IntakeResult intake,
            PostingResult posting,
            ReconResult recon,
            List<StepReport> steps,
            List<FeeRule> rulesAfter,
            List<LedgerEntry> ledgerAfter,
            Map<String, String> status) {

        boolean postingCommitted() {
            return posting != null && posting.report().returnCode() <= 4;
        }
    }

    Result run(ReplayInputs in) {
        Map<String, String> status = new LinkedHashMap<>();
        List<StepReport> steps = new ArrayList<>();
        FeeSchedule fees = schedule.getIfAvailable();
        if (fees != null) {
            fees.seed(in.rules());
        }
        List<FeeRule> rulesAfter = fees == null ? in.rules() : fees.rules();

        TransferIntake step010 = intake.getIfAvailable();
        if (step010 == null) {
            status.put("STEP010", "NOT IMPLEMENTED: no TransferIntake bean");
            return new Result(null, null, null, steps, rulesAfter, in.ledgerBefore(), status);
        }
        IntakeResult extracted = step010.extract(in.transactions(), in.xrefs(), in.accounts());
        record(steps, status, extracted.report());

        AccountPosting step020 = posting.getIfAvailable();
        if (step020 == null) {
            status.put("STEP020", "NOT IMPLEMENTED: no AccountPosting bean");
            return new Result(extracted, null, null, steps, rulesAfter, in.ledgerBefore(), status);
        }
        PostingResult posted = step020.post(extracted.requested(), in.accounts(), in.ledgerBefore());
        record(steps, status, posted.report());
        if (posted.report().returnCode() > 4) {
            status.put("STEP030", "BYPASSED: COND=(4,LT,STEP020)");
            return new Result(extracted, posted, null, steps, rulesAfter, in.ledgerBefore(), status);
        }

        Reconciliation step030 = reconciliation.getIfAvailable();
        if (step030 == null) {
            status.put("STEP030", "NOT IMPLEMENTED: no Reconciliation bean");
            return new Result(extracted, posted, null, steps, rulesAfter, posted.ledgerAfter(), status);
        }
        ReconResult recon = step030.reconcile(posted.posted());
        record(steps, status, recon.report());
        return new Result(extracted, posted, recon, steps, rulesAfter, posted.ledgerAfter(), status);
    }

    private static void record(List<StepReport> steps, Map<String, String> status, StepReport report) {
        steps.add(report);
        status.put(report.step(), "RC=" + report.returnCode());
    }
}
