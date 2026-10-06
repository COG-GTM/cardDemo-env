package com.carddemo.xferfee.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls each service's {@code outbox} table and publishes unpublished rows to Kafka in id order,
 * marking them published in the same DB transaction that locked them. A crash between send and
 * commit republishes the batch; consumers drop the duplicates through their {@code inbox}.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final RelayProperties properties;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
            RelayProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.properties = properties;
        properties.schemas().forEach(schema -> {
            if (!SCHEMA.matcher(schema).matches()) {
                throw new IllegalArgumentException("bad outbox schema name: " + schema);
            }
        });
    }

    private record Row(long id, String topic, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${xferfee.relay.poll-ms:200}")
    public void drain() {
        for (String schema : properties.schemas()) {
            try {
                int published;
                do {
                    published = tx.execute(status -> publishBatch(schema));
                } while (published >= properties.batchSize());
            } catch (BadSqlGrammarException e) {
                LOG.debug("outbox {} not migrated yet", schema);
            }
        }
    }

    /** Number of rows published from {@code schema.outbox} in this transaction. */
    int publishBatch(String schema) {
        List<Row> rows = jdbc.query("SELECT id, topic, msg_key, payload FROM " + schema + ".outbox "
                + "WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                properties.batchSize());
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (Row row : rows) {
            sends.add(kafka.send(row.topic(), row.key(), row.payload()));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("kafka publish failed for " + schema + ".outbox", e);
        }
        jdbc.update("UPDATE " + schema + ".outbox SET published_at = now() WHERE id = ANY (?)",
                (Object) rows.stream().map(Row::id).toArray(Long[]::new));
        LOG.info("relayed {} event(s) from {}.outbox", rows.size(), schema);
        return rows.size();
    }
}
