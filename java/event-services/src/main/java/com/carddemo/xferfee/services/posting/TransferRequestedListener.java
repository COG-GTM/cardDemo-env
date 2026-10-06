package com.carddemo.xferfee.services.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.events.EventEnvelope;
import com.carddemo.xferfee.events.EventJson;
import com.carddemo.xferfee.events.EventTypes;
import com.carddemo.xferfee.events.Inbox;
import com.carddemo.xferfee.events.Outbox;
import com.carddemo.xferfee.events.RunMode;
import com.carddemo.xferfee.events.StepCompleted;
import com.carddemo.xferfee.events.Topics;
import com.carddemo.xferfee.posting.CobolAccountPosting;
import com.carddemo.xferfee.posting.PerTransferPosting;
import com.carddemo.xferfee.services.common.RunTables;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * STEP020 in both COG-1249 D1 modes. batch-atomic buffers the run's transfers and posts them in one
 * transaction when STEP010 completes (abend = nothing but the step report and rejects is written).
 * per-transfer posts each transfer in its own transaction as it arrives and dead-letters failures.
 */
@Component
public class TransferRequestedListener {

    private static final Logger LOG = LoggerFactory.getLogger(TransferRequestedListener.class);

    private final AccountPosting legacy;
    private final PerTransferPosting perTransfer;
    private final PostingStore store;
    private final Inbox inbox;
    private final Outbox outbox;
    private final RunTables runs;

    public TransferRequestedListener(RemoteFeeSchedule feeSchedule, FeePolicy feePolicy, PostingStore store,
            Inbox inbox, Outbox outbox, RunTables runs) {
        this.legacy = new CobolAccountPosting(feeSchedule, feePolicy);
        this.perTransfer = new PerTransferPosting(legacy);
        this.store = store;
        this.inbox = inbox;
        this.outbox = outbox;
        this.runs = runs;
    }

    @KafkaListener(topics = Topics.TRANSFER_REQUESTED, groupId = "xferfee-posting")
    @Transactional
    public void onMessage(String value) {
        EventEnvelope envelope = EventJson.readEnvelope(value);
        if (!inbox.firstDelivery(envelope.messageId())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.TRANSFER_REQUESTED -> {
                TransferRequested transfer = EventJson.payload(envelope, TransferRequested.class);
                if (envelope.mode() == RunMode.PER_TRANSFER) {
                    postOne(envelope, transfer);
                } else {
                    runs.append("pending_transfer", envelope.runId(), envelope.seq(), transfer);
                }
            }
            case EventTypes.STEP_COMPLETED -> completeStep(envelope, EventJson.payload(envelope, StepCompleted.class));
            default -> LOG.warn("ignoring {} on {}", envelope.type(), Topics.TRANSFER_REQUESTED);
        }
    }

    private void postOne(EventEnvelope envelope, TransferRequested transfer) {
        List<Account> master = store.lockMaster();
        List<LedgerEntry> ledger = store.ledger();
        PerTransferPosting.Outcome outcome = perTransfer.postOne(transfer, master, ledger);
        if (outcome instanceof PerTransferPosting.Outcome.Posted posted) {
            store.saveMaster(master, posted.masterAfter());
            store.appendLedger(ledger, posted.ledgerAfter());
            long seq = runs.count("fee_record", envelope.runId()) + 1;
            publishPosted(envelope, seq, posted.posted());
        } else if (outcome instanceof PerTransferPosting.Outcome.Rejected rejected) {
            runs.append("reject", envelope.runId(), envelope.seq(), rejected.rejected());
            publishRejected(envelope, envelope.seq(), rejected.rejected());
        } else {
            LOG.info("run {} {} already posted with identical content (D3), skipped", envelope.runId(),
                    transfer.tranId());
        }
    }

    private void completeStep(EventEnvelope envelope, StepCompleted step010) {
        String runId = envelope.runId();
        StepReport report;
        long postedCount;
        if (envelope.mode() == RunMode.PER_TRANSFER) {
            List<TransferPosted> posted = runs.read("fee_record", runId, TransferPosted.class);
            report = PerTransferPosting.summary(posted, runs.read("reject", runId, TransferRejected.class));
            postedCount = posted.size();
        } else {
            List<TransferRequested> pending = runs.read("pending_transfer", runId, TransferRequested.class);
            List<Account> master = store.lockMaster();
            List<LedgerEntry> ledger = store.ledger();
            AccountPosting.PostingResult result = legacy.post(pending, master, ledger);
            report = result.report();
            if (report.returnCode() <= 4) {
                store.saveMaster(master, result.accountMasterAfter());
                store.appendLedger(ledger, result.ledgerAfter());
                long seq = 0;
                for (TransferPosted posted : result.posted()) {
                    publishPosted(envelope, ++seq, posted);
                }
            }
            long seq = 0;
            for (TransferRejected rejected : result.rejected()) {
                publishRejected(envelope, ++seq, rejected);
            }
            postedCount = report.returnCode() <= 4 ? result.posted().size() : 0;
        }
        runs.saveStep(runId, report);
        List<StepReport> steps = new ArrayList<>(step010.steps());
        steps.add(report);
        outbox.add(Topics.TRANSFER_POSTED, EventJson.envelope(runId, EventTypes.STEP_COMPLETED, envelope.mode(),
                postedCount + 1, new StepCompleted(steps)));
        LOG.info("run {} STEP020 RC={} ({})", runId, report.returnCode(), envelope.mode().wire());
    }

    private void publishPosted(EventEnvelope envelope, long seq, TransferPosted posted) {
        runs.append("fee_record", envelope.runId(), seq, posted);
        outbox.add(Topics.TRANSFER_POSTED, EventJson.envelope(envelope.runId(), EventTypes.TRANSFER_POSTED,
                envelope.mode(), seq, posted));
    }

    private void publishRejected(EventEnvelope envelope, long seq, TransferRejected rejected) {
        outbox.add(Topics.TRANSFER_REJECTED, EventJson.envelope(envelope.runId(), EventTypes.TRANSFER_REJECTED,
                envelope.mode(), seq, rejected));
    }
}
