package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.TransferIntake;
import com.carddemo.xferfee.observability.ChainObserver;
import com.carddemo.xferfee.observability.ChainRunReport;
import com.carddemo.xferfee.observability.ChainStep;
import com.carddemo.xferfee.parity.interim.InterimAccountPosting;
import com.carddemo.xferfee.parity.interim.InterimFeePolicy;
import com.carddemo.xferfee.parity.interim.InterimFeeSchedule;
import com.carddemo.xferfee.parity.interim.InterimReconciliation;
import com.carddemo.xferfee.parity.interim.InterimTransferIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs XFERFEEP's three steps in-process over the {@code contracts} SPIs and feeds every step
 * result to {@link ChainObserver}. A contract bean contributed by a service module wins; until
 * those modules land the {@code interim} implementations stand in (logged at startup).
 */
@Component
public class ChainReplay {

    private static final Logger LOG = LoggerFactory.getLogger(ChainReplay.class);

    private final ObjectProvider<TransferIntake> intake;
    private final ObjectProvider<AccountPosting> posting;
    private final ObjectProvider<Reconciliation> reconciliation;
    private final ObjectProvider<FeeSchedule> feeSchedule;
    private final ObjectProvider<FeePolicy> feePolicy;
    private final ChainObserver observer;

    public ChainReplay(ObjectProvider<TransferIntake> intake, ObjectProvider<AccountPosting> posting,
            ObjectProvider<Reconciliation> reconciliation, ObjectProvider<FeeSchedule> feeSchedule,
            ObjectProvider<FeePolicy> feePolicy, ChainObserver observer) {
        this.intake = intake;
        this.posting = posting;
        this.reconciliation = reconciliation;
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.observer = observer;
    }

    public ChainRunReport run(FixtureInputs inputs) {
        observer.startRun(inputs.runDate());

        TransferIntake.IntakeResult extracted = pick(intake, InterimTransferIntake::new, "TransferIntake")
                .extract(inputs.dailyTransactions(), inputs.cardXrefs(), inputs.accounts());
        observer.intake(inputs.dailyTransactions(), extracted);

        FeeSchedule schedule = pick(feeSchedule, InterimFeeSchedule::new, "FeeSchedule");
        schedule.seed(inputs.feeRules());
        FeePolicy policy = pick(feePolicy, InterimFeePolicy::new, "FeePolicy");
        AccountPosting.PostingResult posted = pick(posting, () -> new InterimAccountPosting(schedule, policy),
                "AccountPosting").post(extracted.requested(), inputs.accounts(), inputs.ledgerBefore());
        observer.posting(posted);

        // //STEP030 EXEC PGM=CBXFR03C,COND=(4,LT,STEP020)
        if (4 < posted.report().returnCode()) {
            observer.skipped(ChainStep.STEP030);
        } else {
            Reconciliation.ReconResult recon = pick(reconciliation, InterimReconciliation::new, "Reconciliation")
                    .reconcile(posted.posted());
            observer.reconciliation(posted.posted(), recon);
        }
        return observer.report();
    }

    private static <T> T pick(ObjectProvider<T> provider, java.util.function.Supplier<T> interim, String name) {
        T bean = provider.getIfAvailable();
        if (bean != null) {
            return bean;
        }
        LOG.info("No {} bean; using the interim parity-replay implementation", name);
        return interim.get();
    }
}
