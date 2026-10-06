package com.carddemo.xferfee.services.intake;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.carddemo.xferfee.events.EventEnvelope;
import com.carddemo.xferfee.events.EventJson;
import com.carddemo.xferfee.events.EventTypes;
import com.carddemo.xferfee.events.Inbox;
import com.carddemo.xferfee.events.Outbox;
import com.carddemo.xferfee.events.StepCompleted;
import com.carddemo.xferfee.events.Topics;
import com.carddemo.xferfee.intake.CobolTransferIntake;
import com.carddemo.xferfee.services.common.RunTables;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each daily record is selected in its own DB transaction (the extract row, the reject row and the
 * outgoing event commit together); {@code BatchClosed} closes STEP010 with CBXFR01C's counters.
 */
@Component
public class DailyTransactionListener {

    private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionListener.class);

    private final CobolTransferIntake intake = new CobolTransferIntake();
    private final JdbcTemplate jdbc;
    private final Inbox inbox;
    private final Outbox outbox;
    private final RunTables runs;

    public DailyTransactionListener(JdbcTemplate jdbc, Inbox inbox, Outbox outbox, RunTables runs) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.outbox = outbox;
        this.runs = runs;
    }

    @KafkaListener(topics = Topics.DAILY_TRANSACTIONS, groupId = "xferfee-intake")
    @Transactional
    public void onMessage(String value) {
        EventEnvelope envelope = EventJson.readEnvelope(value);
        if (!inbox.firstDelivery(envelope.messageId())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.DAILY_TRANSACTION -> select(envelope);
            case EventTypes.BATCH_CLOSED -> close(envelope);
            default -> LOG.warn("ignoring {} on {}", envelope.type(), Topics.DAILY_TRANSACTIONS);
        }
    }

    private void select(EventEnvelope envelope) {
        DailyTransaction transaction = EventJson.payload(envelope, DailyTransaction.class);
        runs.append("daily_transaction", envelope.runId(), envelope.seq(), transaction);
        CobolTransferIntake.Selection selection = intake.select(transaction, referenceData());
        selection.requested().ifPresent(requested -> {
            runs.append("extract_record", envelope.runId(), envelope.seq(), requested);
            outbox.add(Topics.TRANSFER_REQUESTED, EventJson.envelope(envelope.runId(),
                    EventTypes.TRANSFER_REQUESTED, envelope.mode(), envelope.seq(), requested));
        });
        selection.rejected().ifPresent(rejected -> {
            jdbc.update("INSERT INTO intake.reject (run_id, seq, payload, sysout_line) VALUES (?, ?, ?, ?)",
                    envelope.runId(), envelope.seq(), EventJson.write(rejected), selection.sysoutLine().orElse(""));
            outbox.add(Topics.TRANSFER_REJECTED, EventJson.envelope(envelope.runId(),
                    EventTypes.TRANSFER_REJECTED, envelope.mode(), envelope.seq(), rejected));
        });
    }

    private void close(EventEnvelope envelope) {
        String runId = envelope.runId();
        List<String> rejectLines = jdbc.queryForList(
                "SELECT sysout_line FROM intake.reject WHERE run_id = ? ORDER BY seq", String.class, runId);
        List<TransferRejected> rejected = runs.read("reject", runId, TransferRejected.class);
        StepReport report = CobolTransferIntake.report(rejectLines, runs.count("daily_transaction", runId),
                runs.count("extract_record", runId), rejected.size());
        runs.saveStep(runId, report);
        outbox.add(Topics.TRANSFER_REQUESTED, EventJson.envelope(runId, EventTypes.STEP_COMPLETED,
                envelope.mode(), envelope.seq(), new StepCompleted(List.of(report))));
        LOG.info("run {} STEP010 RC={}", runId, report.returnCode());
    }

    private CobolTransferIntake.ReferenceData referenceData() {
        List<CardXref> xrefs = jdbc.query("SELECT card_num, cust_id, acct_id FROM intake.card_xref ORDER BY seq",
                (rs, n) -> new CardXref(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        List<Account> accounts = jdbc.query("SELECT payload FROM intake.account_ref ORDER BY seq",
                (rs, n) -> EventJson.read(rs.getString(1), Account.class));
        return new CobolTransferIntake.ReferenceData(xrefs, accounts);
    }
}
