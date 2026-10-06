package com.carddemo.xferfee.events;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transactional outbox writer: the caller's DB transaction stores the event next to the state
 * change it describes; {@code outbox-relay} publishes it to Kafka afterwards (at-least-once).
 */
public final class Outbox {

    private final JdbcTemplate jdbc;
    private final String table;

    public Outbox(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.table = schema + ".outbox";
    }

    public void add(String topic, EventEnvelope envelope) {
        jdbc.update("INSERT INTO " + table + " (topic, msg_key, payload) VALUES (?, ?, ?)",
                topic, envelope.runId(), EventJson.write(envelope));
    }
}
