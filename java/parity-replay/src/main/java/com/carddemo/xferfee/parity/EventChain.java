package com.carddemo.xferfee.parity;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.CardXref;
import com.carddemo.xferfee.contracts.DailyTransaction;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.StepReport;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import com.carddemo.xferfee.events.BatchClosed;
import com.carddemo.xferfee.events.EventEnvelope;
import com.carddemo.xferfee.events.EventJson;
import com.carddemo.xferfee.events.EventTypes;
import com.carddemo.xferfee.events.RunCompleted;
import com.carddemo.xferfee.events.RunMode;
import com.carddemo.xferfee.events.Topics;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code --mode=events}: loads the fixture's master files and CTL_XFER_PARM into the services'
 * Postgres schemas (the role {@code runjcl --load-fixtures --db-reset} plays for COBOL), publishes
 * the daily file to Kafka, waits for {@code RunCompleted} and reads the outputs back from the
 * service tables.
 */
final class EventChain {

    /** XFERFEE 3000-WRITE-MASTER writes only the first 500 accounts it loaded (WS-ACCOUNT-ROW OCCURS 500). */
    static final int ACCOUNT_TABLE_LIMIT = 500;

    private static final Logger LOG = LoggerFactory.getLogger(EventChain.class);

    private final ReplayOptions options;

    EventChain(ReplayOptions options) {
        this.options = options;
    }

    ChainOutput run(CaseInput input, RunMode mode) throws Exception {
        String runId = options.caseName() + "-" + UUID.randomUUID();
        ensureTopics();
        try (Connection db = DriverManager.getConnection(options.dbUrl(), options.dbUser(), options.dbPassword())) {
            seed(db, input);
        }
        RunCompleted completed;
        try (KafkaConsumer<String, String> status = statusConsumer();
                KafkaProducer<String, String> producer = producer()) {
            long seq = 0;
            for (DailyTransaction transaction : input.dailyTransactions()) {
                send(producer, EventJson.envelope(runId, EventTypes.DAILY_TRANSACTION, mode, ++seq, transaction));
            }
            send(producer, EventJson.envelope(runId, EventTypes.BATCH_CLOSED, mode, ++seq,
                    new BatchClosed(input.dailyTransactions().size())));
            producer.flush();
            LOG.info("run {} published {} daily records ({})", runId, input.dailyTransactions().size(), mode.wire());
            completed = awaitCompletion(status, runId);
        }
        try (Connection db = DriverManager.getConnection(options.dbUrl(), options.dbUser(), options.dbPassword())) {
            return collect(db, runId, completed.steps());
        }
    }

    private void send(KafkaProducer<String, String> producer, EventEnvelope envelope)
            throws InterruptedException, ExecutionException {
        producer.send(new ProducerRecord<>(Topics.DAILY_TRANSACTIONS, envelope.runId(), EventJson.write(envelope)))
                .get();
    }

    private RunCompleted awaitCompletion(KafkaConsumer<String, String> status, String runId) {
        Instant deadline = Instant.now().plus(options.timeout());
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : status.poll(Duration.ofMillis(500))) {
                EventEnvelope envelope = EventJson.readEnvelope(record.value());
                if (runId.equals(envelope.runId()) && EventTypes.RUN_COMPLETED.equals(envelope.type())) {
                    return EventJson.payload(envelope, RunCompleted.class);
                }
            }
        }
        throw new IllegalStateException("timed out after " + options.timeout() + " waiting for RunCompleted " + runId);
    }

    private void ensureTopics() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers());
        try (Admin admin = Admin.create(props)) {
            for (String topic : Topics.ALL) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, Topics.PARTITIONS, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
    }

    /** Subscribed by assignment and positioned at the end before anything is published. */
    private KafkaConsumer<String, String> statusConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor(Topics.RUN_STATUS).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    static void seed(Connection db, CaseInput input) throws SQLException {
        db.setAutoCommit(false);
        try (Statement statement = db.createStatement()) {
            statement.execute("TRUNCATE fee_schedule.ctl_xfer_parm, intake.card_xref, intake.account_ref, "
                    + "posting.account, posting.xfer_fee_ledger");
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO fee_schedule.ctl_xfer_parm "
                + "(book_id, fee_pct, fee_cap, eff_dt, exp_dt) VALUES (?, ?, ?, ?, ?)")) {
            for (FeeRule rule : input.feeRules()) {
                insert.setString(1, rule.bookId().stripTrailing());
                insert.setBigDecimal(2, rule.feePct());
                insert.setBigDecimal(3, rule.feeCap());
                insert.setDate(4, Date.valueOf(rule.effectiveDate()));
                insert.setDate(5, Date.valueOf(rule.expiryDate()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO intake.card_xref (seq, card_num, cust_id, acct_id) VALUES (?, ?, ?, ?)")) {
            int seq = 0;
            for (CardXref xref : input.cardXrefs()) {
                insert.setInt(1, ++seq);
                insert.setString(2, xref.cardNumber());
                insert.setLong(3, xref.customerId());
                insert.setLong(4, xref.accountId());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement reference = db.prepareStatement(
                "INSERT INTO intake.account_ref (seq, payload) VALUES (?, ?)");
                PreparedStatement master = db.prepareStatement("INSERT INTO posting.account (seq, acct_id, payload) "
                        + "VALUES (?, ?, ?)")) {
            int seq = 0;
            for (Account account : input.accounts()) {
                seq++;
                reference.setInt(1, seq);
                reference.setString(2, EventJson.write(account));
                reference.addBatch();
                master.setInt(1, seq);
                master.setLong(2, account.accountId());
                master.setString(3, EventJson.write(account));
                master.addBatch();
            }
            reference.executeBatch();
            master.executeBatch();
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO posting.xfer_fee_ledger (tran_id, tran_dt, "
                + "src_acct_id, tgt_acct_id, book_id, tran_amt, fee_amt, cap_applied) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (LedgerEntry entry : input.ledgerBefore()) {
                insert.setString(1, entry.tranId().stripTrailing());
                insert.setDate(2, Date.valueOf(entry.tranDate()));
                insert.setLong(3, entry.sourceAccountId());
                insert.setLong(4, entry.targetAccountId());
                insert.setString(5, entry.bookId().stripTrailing());
                insert.setBigDecimal(6, entry.amount());
                insert.setBigDecimal(7, entry.feeAmount());
                insert.setString(8, entry.capApplied() ? "Y" : "N");
                insert.addBatch();
            }
            insert.executeBatch();
        }
        db.commit();
    }

    static ChainOutput collect(Connection db, String runId, List<StepReport> steps) throws SQLException {
        Optional<StepReport> step010 = step(steps, "STEP010");
        Optional<StepReport> step020 = step(steps, "STEP020");
        Optional<StepReport> step030 = step(steps, "STEP030");
        List<TransferRequested> extract = step010.filter(ChainOutput::kept).isPresent()
                ? payloads(db, "SELECT payload FROM intake.extract_record WHERE run_id = ? ORDER BY seq", runId,
                        TransferRequested.class)
                : null;
        boolean postingKept = step020.filter(ChainOutput::kept).isPresent();
        List<Account> master = postingKept
                ? payloads(db, "SELECT payload FROM posting.account ORDER BY seq LIMIT " + ACCOUNT_TABLE_LIMIT, null,
                        Account.class)
                : null;
        List<TransferPosted> fees = postingKept
                ? payloads(db, "SELECT payload FROM posting.fee_record WHERE run_id = ? ORDER BY seq", runId,
                        TransferPosted.class)
                : null;
        List<String> report = step030.filter(ChainOutput::kept).isPresent()
                ? strings(db, "SELECT text FROM recon.report_line WHERE run_id = ? ORDER BY line_no", runId)
                : null;
        return new ChainOutput(steps, extract, master, fees, report, rules(db), ledger(db));
    }

    private static Optional<StepReport> step(List<StepReport> steps, String name) {
        return steps.stream().filter(s -> s.step().equals(name)).findFirst();
    }

    private static <T> List<T> payloads(Connection db, String sql, String runId, Class<T> type) throws SQLException {
        List<T> rows = new ArrayList<>();
        for (String json : strings(db, sql, runId)) {
            rows.add(EventJson.read(json, type));
        }
        return rows;
    }

    private static List<String> strings(Connection db, String sql, String runId) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            if (runId != null) {
                query.setString(1, runId);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        }
    }

    private static List<FeeRule> rules(Connection db) throws SQLException {
        List<FeeRule> rules = new ArrayList<>();
        try (Statement query = db.createStatement();
                ResultSet rs = query.executeQuery("SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt "
                        + "FROM fee_schedule.ctl_xfer_parm ORDER BY book_id, eff_dt")) {
            while (rs.next()) {
                rules.add(new FeeRule(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate()));
            }
        }
        return rules;
    }

    private static List<LedgerEntry> ledger(Connection db) throws SQLException {
        List<LedgerEntry> entries = new ArrayList<>();
        try (Statement query = db.createStatement();
                ResultSet rs = query.executeQuery("SELECT tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, "
                        + "tran_amt, fee_amt, cap_applied FROM posting.xfer_fee_ledger ORDER BY tran_id")) {
            while (rs.next()) {
                entries.add(new LedgerEntry(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        "Y".equals(rs.getString(8))));
            }
        }
        return entries;
    }
}
