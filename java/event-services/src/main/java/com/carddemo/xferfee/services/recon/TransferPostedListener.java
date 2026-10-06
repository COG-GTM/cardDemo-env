package com.carddemo.xferfee.services.recon;

import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.events.EventEnvelope;
import com.carddemo.xferfee.events.EventJson;
import com.carddemo.xferfee.events.EventTypes;
import com.carddemo.xferfee.events.Inbox;
import com.carddemo.xferfee.events.Outbox;
import com.carddemo.xferfee.events.RunCompleted;
import com.carddemo.xferfee.events.StepCompleted;
import com.carddemo.xferfee.events.Topics;
import com.carddemo.xferfee.recon.CobolReconciliation;
import com.carddemo.xferfee.services.common.RunTables;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Collects the run's fees in posting order; on STEP020 completion applies {@code COND=(4,LT,STEP020)}. */
@Component
public class TransferPostedListener {

    private static final Logger LOG = LoggerFactory.getLogger(TransferPostedListener.class);

    private final CobolReconciliation reconciliation = new CobolReconciliation();
    private final JdbcTemplate jdbc;
    private final Inbox inbox;
    private final Outbox outbox;
    private final RunTables runs;

    public TransferPostedListener(JdbcTemplate jdbc, Inbox inbox, Outbox outbox, RunTables runs) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.outbox = outbox;
        this.runs = runs;
    }

    @KafkaListener(topics = Topics.TRANSFER_POSTED, groupId = "xferfee-recon")
    @Transactional
    public void onMessage(String value) {
        EventEnvelope envelope = EventJson.readEnvelope(value);
        if (!inbox.firstDelivery(envelope.messageId())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.TRANSFER_POSTED -> runs.append("posted_fee", envelope.runId(), envelope.seq(),
                    EventJson.payload(envelope, TransferPosted.class));
            case EventTypes.STEP_COMPLETED -> complete(envelope, EventJson.payload(envelope, StepCompleted.class));
            default -> LOG.warn("ignoring {} on {}", envelope.type(), Topics.TRANSFER_POSTED);
        }
    }

    private void complete(EventEnvelope envelope, StepCompleted upstream) {
        String runId = envelope.runId();
        List<StepReport> steps = new ArrayList<>(upstream.steps());
        StepReport step020 = upstream.last();
        if (4 < step020.returnCode()) {
            LOG.info("run {} STEP030 bypassed: COND=(4,LT,STEP020) with STEP020 RC={}", runId, step020.returnCode());
        } else {
            CobolReconciliation.ReconResult result =
                    reconciliation.reconcile(runs.read("posted_fee", runId, TransferPosted.class));
            int lineNo = 0;
            for (String line : result.reportLines()) {
                jdbc.update("INSERT INTO recon.report_line (run_id, line_no, text) VALUES (?, ?, ?)",
                        runId, ++lineNo, line);
            }
            runs.saveStep(runId, result.report());
            steps.add(result.report());
            LOG.info("run {} STEP030 RC={}", runId, result.report().returnCode());
        }
        outbox.add(Topics.RUN_STATUS, EventJson.envelope(runId, EventTypes.RUN_COMPLETED, envelope.mode(),
                1, new RunCompleted(runId, steps)));
    }
}
