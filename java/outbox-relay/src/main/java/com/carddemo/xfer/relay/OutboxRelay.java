package com.carddemo.xfer.relay;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes committed outbox rows in id order; a row is marked only after the broker acks it. */
@Component
public class OutboxRelay {

    private record Row(long id, String topic, String key, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 100)
    @Transactional
    public void relay() throws Exception {
        List<Row> rows = jdbc.query(
                "SELECT id, topic, msg_key, payload FROM xfer_java.outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        for (Row row : rows) {
            kafka.send(row.topic(), row.key(), row.payload()).get(30, TimeUnit.SECONDS);
            jdbc.update("UPDATE xfer_java.outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?",
                    row.id());
        }
    }
}
