package com.carddemo.xferfee.shadow;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.AccountPosting.PostingResult;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.Reconciliation.ReconResult;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.contracts.TransferIntake.IntakeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XFRDAILY driven through the frozen step SPIs: STEP010 intake, STEP020 posting, STEP030 reconciliation, stopping
 * at the first non-zero step RC like the JCL harness (no COND= overrides in the proc).
 */
public final class ShadowChain {

    private final TransferIntake intake;
    private final FeeSchedule schedule;
    private final AccountPosting posting;
    private final Reconciliation reconciliation;

    public ShadowChain(TransferIntake intake, FeeSchedule schedule, AccountPosting posting,
            Reconciliation reconciliation) {
        this.intake = intake;
        this.schedule = schedule;
        this.posting = posting;
        this.reconciliation = reconciliation;
    }

    /** The legacy-faithful implementation of every step. */
    public static ShadowChain legacy() {
        SnapshotFeeSchedule schedule = new SnapshotFeeSchedule();
        return new ShadowChain(new LegacyTransferIntake(), schedule,
                new LegacyAccountPosting(schedule, new LegacyFeePolicy()), new LegacyReconciliation());
    }

    public ChainResult run(List<DailyTransaction> transactions, List<CardXref> xrefs, List<Account> accounts,
            List<FeeRule> rules, List<LedgerEntry> ledgerBefore) {
        schedule.seed(rules);
        Map<String, List<String>> sysout = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> rc = new LinkedHashMap<>();

        IntakeResult step010 = intake.extract(transactions, xrefs, accounts);
        record(step010.report(), sysout, rc);
        if (step010.report().returnCode() != 0) {
            return new ChainResult(step010.requested(), step010.rejected(), null, null, null, schedule.rules(),
                    ledgerBefore, sysout, rc);
        }

        PostingResult step020 = posting.post(step010.requested(), accounts, ledgerBefore);
        record(step020.report(), sysout, rc);
        if (step020.report().returnCode() != 0) {
            return new ChainResult(step010.requested(), step020.rejected(), step020.accountMasterAfter(),
                    step020.posted(), null, schedule.rules(), step020.ledgerAfter(), sysout, rc);
        }

        ReconResult step030 = reconciliation.reconcile(step020.posted());
        record(step030.report(), sysout, rc);
        return new ChainResult(step010.requested(), step020.rejected(), step020.accountMasterAfter(),
                step020.posted(), step030.reportLines(), schedule.rules(), step020.ledgerAfter(), sysout, rc);
    }

    private static void record(StepReport report, Map<String, List<String>> sysout, Map<String, Integer> rc) {
        sysout.put(report.step(), report.sysout());
        rc.put(report.step(), report.returnCode());
    }
}
