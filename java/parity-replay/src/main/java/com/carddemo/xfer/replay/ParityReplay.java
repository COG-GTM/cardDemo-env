package com.carddemo.xfer.replay;

import com.carddemo.xfer.contracts.Account;
import com.carddemo.xfer.contracts.DailyTranReceived;
import com.carddemo.xfer.contracts.EndOfDay;
import com.carddemo.xfer.contracts.Topics;
import com.carddemo.xfer.contracts.TransferPosted;
import com.carddemo.xfer.contracts.TransferRequested;
import com.carddemo.xfer.contracts.XferJson;
import com.carddemo.xfer.legacy.AccountCodec;
import com.carddemo.xfer.legacy.CardXrefRecord;
import com.carddemo.xfer.legacy.DailyTranRecord;
import com.carddemo.xfer.legacy.ExtractCodec;
import com.carddemo.xfer.legacy.FeeRecordCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/**
 * {@code parity-replay --mode=events}: loads a fixture's legacy inputs, publishes its DALYTRAN
 * records to Kafka, waits for the services to close the run and writes a candidate directory in
 * the layout tools/parity/compare.py expects.
 */
public final class ParityReplay {

    static final String EXTRACT = "AWS.M2.CARDDEMO.XFER.EXTRACT.G0001V00";
    static final String ACCTOUT = "AWS.M2.CARDDEMO.ACCTDATA.XFER.G0001V00";
    static final String FEES = "AWS.M2.CARDDEMO.XFER.FEES.G0001V00";
    static final String RECON = "AWS.M2.CARDDEMO.XFER.RECON.RPT.G0001V00";
    static final List<String> STEPS = List.of("STEP010", "STEP020", "STEP030");
    static final List<String> SERVICES = List.of("fee-schedule-service", "transfer-intake-service",
            "account-posting-service", "outbox-relay", "reconciliation-service");
    static final int TABLE_LIMIT = 500;
    static final long SCHEMA_LOCK_ID = 1_251_001L;

    private final Map<String, String> opts;

    private ParityReplay(Map<String, String> opts) {
        this.opts = opts;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new HashMap<>();
        opts.put("fixtures", "fixtures/xferfee");
        opts.put("timeout", "180");
        opts.put("bootstrap", env("SPRING_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        opts.put("jdbc", env("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/carddemo"));
        opts.put("user", env("SPRING_DATASOURCE_USERNAME", "carddemo"));
        opts.put("password", env("SPRING_DATASOURCE_PASSWORD", "carddemo"));
        opts.put("posting-mode", env("XFER_POSTING_MODE", "batch-atomic"));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + arg);
            }
            String key = arg.substring(2);
            if (key.contains("=")) {
                opts.put(key.substring(0, key.indexOf('=')), key.substring(key.indexOf('=') + 1));
            } else {
                opts.put(key, args[++i]);
            }
        }
        if (!"events".equals(opts.get("mode"))) {
            System.err.println("usage: parity-replay --mode=events --case <case> --out <dir>");
            System.exit(2);
        }
        System.exit(new ParityReplay(opts).run());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int run() throws Exception {
        String caseName = require("case");
        Path fixture = Path.of(opts.get("fixtures"), caseName);
        Path out = Path.of(require("out"));
        String runId = caseName + "-" + UUID.randomUUID();
        Duration timeout = Duration.ofSeconds(Long.parseLong(opts.get("timeout")));
        try (Connection db = DriverManager.getConnection(
                opts.get("jdbc"), opts.get("user"), opts.get("password"))) {
            applySchema(db);
            waitForServices(db, timeout);
            List<DailyTranRecord> trans = resetAndLoad(db, fixture);
            ensureTopics();
            publish(runId, trans);
            System.out.printf("parity-replay: %s published %d DALYTRAN records as run %s%n",
                    caseName, trans.size(), runId);
            Map<String, Integer> rcs = awaitRun(db, runId, timeout);
            writeCandidate(db, runId, rcs, out);
            System.out.printf("parity-replay: %s candidate %s rc=%s%n", caseName, out, rcs);
        }
        return 0;
    }

    private String require(String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return value;
    }

    private static void applySchema(Connection db) throws IOException, SQLException {
        String script;
        try (InputStream in = ParityReplay.class.getResourceAsStream("/xfer-java-schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(" + SCHEMA_LOCK_ID + ")");
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
        db.commit();
        db.setAutoCommit(true);
    }

    private static void waitForServices(Connection db, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            List<String> ready = new ArrayList<>();
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("SELECT service FROM xfer_java.service_ready")) {
                while (rs.next()) {
                    ready.add(rs.getString(1));
                }
            }
            if (ready.containsAll(SERVICES)) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("services not ready: " + ready);
            }
            Thread.sleep(500);
        }
    }

    /** Same reset as tools/parity/recorder.py, plus the legacy VSAM/PS inputs as tables. */
    private static List<DailyTranRecord> resetAndLoad(Connection db, Path fixture) throws Exception {
        Path input = fixture.resolve("input");
        List<DailyTranRecord> trans = new ArrayList<>();
        for (byte[] r : records(input.resolve("DALYTRAN.PS"), DailyTranRecord.LENGTH)) {
            trans.add(DailyTranRecord.decode(r));
        }
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("TRUNCATE xfer_java.card_xref, xfer_java.account");
            st.execute("TRUNCATE XFER_FEE_LEDGER");
            st.execute("DELETE FROM CTL_XFER_PARM");
        }
        CopyManager copy = db.unwrap(PGConnection.class).getCopyAPI();
        for (String table : List.of("CTL_XFER_PARM", "XFER_FEE_LEDGER")) {
            Path csv = fixture.resolve("db2_before").resolve(table + ".csv");
            String header = Files.readAllLines(csv).get(0);
            try (Reader reader = Files.newBufferedReader(csv)) {
                copy.copyIn("COPY " + table + " (" + header + ") FROM STDIN WITH CSV HEADER", reader);
            }
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO xfer_java.card_xref (seq, card_num, cust_id, acct_id) VALUES (?, ?, ?, ?)")) {
            int seq = 0;
            for (byte[] r : records(input.resolve("CARDXREF.PS"), CardXrefRecord.LENGTH)) {
                CardXrefRecord x = CardXrefRecord.decode(r);
                ps.setInt(1, ++seq);
                ps.setString(2, x.cardNum());
                ps.setLong(3, x.custId());
                ps.setLong(4, x.acctId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO xfer_java.account (seq, acct_id, active_status, curr_bal, credit_limit, "
                        + "cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, "
                        + "curr_cyc_debit, addr_zip, group_id, raw) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (byte[] r : records(input.resolve("ACCTDATA.PS"), AccountCodec.LENGTH)) {
                Account a = AccountCodec.decode(r);
                ps.setInt(1, ++seq);
                ps.setLong(2, a.acctId());
                ps.setString(3, a.activeStatus());
                ps.setBigDecimal(4, a.currBal());
                ps.setBigDecimal(5, a.creditLimit());
                ps.setBigDecimal(6, a.cashCreditLimit());
                ps.setString(7, a.openDate());
                ps.setString(8, a.expirationDate());
                ps.setString(9, a.reissueDate());
                ps.setBigDecimal(10, a.currCycCredit());
                ps.setBigDecimal(11, a.currCycDebit());
                ps.setString(12, a.addrZip());
                ps.setString(13, a.groupId());
                ps.setBytes(14, r);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        db.commit();
        db.setAutoCommit(true);
        return trans;
    }

    private static List<byte[]> records(Path file, int length) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length % length != 0) {
            throw new IllegalStateException(file + " is not a multiple of " + length + " bytes");
        }
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < data.length; i += length) {
            byte[] r = new byte[length];
            System.arraycopy(data, i, r, 0, length);
            out.add(r);
        }
        return out;
    }

    private void ensureTopics() throws InterruptedException, ExecutionException {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, opts.get("bootstrap"));
        try (Admin admin = Admin.create(props)) {
            for (String topic : List.of(Topics.DAILY_TRAN, Topics.TRANSFER_REQUESTED, Topics.TRANSFER_POSTED)) {
                try {
                    admin.createTopics(Set.of(new NewTopic(topic, 1, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
    }

    private void publish(String runId, List<DailyTranRecord> trans) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, opts.get("bootstrap"));
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long seq = 0;
            for (DailyTranRecord t : trans) {
                DailyTranReceived event = new DailyTranReceived(runId, ++seq, t.tranId(),
                        t.tranTypeCd(), t.tranDesc(), t.tranAmt(), t.tranCardNum(), t.tranOrigTs());
                producer.send(new ProducerRecord<>(Topics.DAILY_TRAN, runId, XferJson.write(event))).get();
            }
            producer.send(new ProducerRecord<>(Topics.DAILY_TRAN, runId,
                    XferJson.write(new EndOfDay(runId, seq)))).get();
        }
    }

    private Map<String, Integer> awaitRun(Connection db, String runId, Duration timeout) throws Exception {
        int maxPostingRc = "per-transfer".equalsIgnoreCase(opts.get("posting-mode")) ? 4 : 0;
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Map<String, Integer> rcs = stepRcs(db, runId);
            boolean extractStopped = rcs.getOrDefault("STEP010", 0) != 0 && maxPostingRc == 0;
            boolean postingStopped = rcs.containsKey("STEP020") && rcs.get("STEP020") > maxPostingRc;
            if (rcs.containsKey("STEP030") || extractStopped || postingStopped) {
                return rcs;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("run " + runId + " did not finish; steps " + rcs);
            }
            Thread.sleep(200);
        }
    }

    private static Map<String, Integer> stepRcs(Connection db, String runId) throws SQLException {
        Map<String, Integer> rcs = new LinkedHashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT step, rc FROM xfer_java.run_step WHERE run_id = ? ORDER BY step")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rcs.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return rcs;
    }

    private static void writeCandidate(Connection db, String runId, Map<String, Integer> rcs, Path out)
            throws Exception {
        Path datasets = out.resolve("datasets");
        Path sysout = out.resolve("sysout");
        Path dbAfter = out.resolve("db2_after");
        for (Path dir : List.of(datasets, sysout, dbAfter)) {
            Files.createDirectories(dir);
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
        }
        if (rcs.containsKey("STEP010")) {
            ByteArrayOutputStream extract = new ByteArrayOutputStream();
            for (TransferRequested t : extractRecords(db, runId)) {
                extract.write(ExtractCodec.encode(t));
            }
            Files.write(datasets.resolve(EXTRACT), extract.toByteArray());
        }
        if (rcs.containsKey("STEP020")) {
            ByteArrayOutputStream fees = new ByteArrayOutputStream();
            Set<Long> sources = new HashSet<>();
            Set<Long> targets = new HashSet<>();
            for (TransferPosted p : feeRecords(db, runId)) {
                fees.write(FeeRecordCodec.encode(p));
                sources.add(p.srcAcctId());
                targets.add(p.tgtAcctId());
            }
            Files.write(datasets.resolve(FEES), fees.toByteArray());
            ByteArrayOutputStream accounts = new ByteArrayOutputStream();
            // XFERFEE writes the account master only after the posting loop completes.
            if (rcs.get("STEP020") <= 4) {
                for (Map.Entry<Account, byte[]> a : accounts(db)) {
                    long id = a.getKey().acctId();
                    accounts.write(AccountCodec.rewrite(a.getValue(), a.getKey(),
                            sources.contains(id), targets.contains(id)));
                }
            }
            Files.write(datasets.resolve(ACCTOUT), accounts.toByteArray());
        }
        if (rcs.containsKey("STEP030")) {
            Files.write(datasets.resolve(RECON), lines(db,
                    "SELECT line FROM xfer_java.recon_report_line WHERE run_id = ? ORDER BY line_no", runId));
        }
        for (String step : STEPS) {
            if (rcs.containsKey(step)) {
                Files.write(sysout.resolve(step + ".txt"), lines(db,
                        "SELECT line FROM xfer_java.run_sysout WHERE run_id = ? AND step = '" + step
                                + "' ORDER BY line_no", runId));
            }
        }
        CopyManager copy = db.unwrap(PGConnection.class).getCopyAPI();
        dump(copy, dbAfter.resolve("CTL_XFER_PARM.csv"),
                "SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt FROM CTL_XFER_PARM ORDER BY book_id, eff_dt");
        dump(copy, dbAfter.resolve("XFER_FEE_LEDGER.csv"),
                "SELECT tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, tran_amt, fee_amt, "
                        + "cap_applied FROM XFER_FEE_LEDGER ORDER BY tran_id");
        StringBuilder json = new StringBuilder("{\n  \"steps\": {");
        int maxcc = 0;
        String sep = "\n";
        for (Map.Entry<String, Integer> e : rcs.entrySet()) {
            json.append(sep).append("    \"").append(e.getKey()).append("\": ").append(e.getValue());
            sep = ",\n";
            maxcc = Math.max(maxcc, e.getValue());
        }
        json.append("\n  },\n  \"maxcc\": ").append(maxcc).append("\n}\n");
        Files.writeString(out.resolve("rc.json"), json);
    }

    private static void dump(CopyManager copy, Path file, String query) throws Exception {
        try (OutputStream os = Files.newOutputStream(file)) {
            copy.copyOut("COPY (" + query + ") TO STDOUT WITH CSV HEADER", os);
        }
    }

    private static byte[] lines(Connection db, String query, String runId) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (PreparedStatement ps = db.prepareStatement(query)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    text.append(rs.getString(1)).append('\n');
                }
            }
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static List<TransferRequested> extractRecords(Connection db, String runId) throws SQLException {
        List<TransferRequested> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT seq, tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, tran_amt, card_num "
                        + "FROM xfer_java.extract_record WHERE run_id = ? ORDER BY seq")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransferRequested(runId, rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getString(8)));
                }
            }
        }
        return out;
    }

    private static List<TransferPosted> feeRecords(Connection db, String runId) throws SQLException {
        List<TransferPosted> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT seq, tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, tran_amt, fee_pct, "
                        + "fee_amt, cap_applied, rule_eff_dt FROM xfer_java.fee_record "
                        + "WHERE run_id = ? ORDER BY seq")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TransferPosted(runId, rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getBigDecimal(7),
                            rs.getBigDecimal(8), rs.getBigDecimal(9), "Y".equals(rs.getString(10)),
                            rs.getString(11)));
                }
            }
        }
        return out;
    }

    private static List<Map.Entry<Account, byte[]>> accounts(Connection db) throws SQLException {
        List<Map.Entry<Account, byte[]>> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, "
                        + "expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, "
                        + "group_id, raw FROM xfer_java.account WHERE seq <= ? ORDER BY seq")) {
            ps.setInt(1, TABLE_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Map.entry(new Account(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11),
                            rs.getString(12)), rs.getBytes(13)));
                }
            }
        }
        return out;
    }
}
