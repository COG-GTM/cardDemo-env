package com.carddemo.xferfee.events;

import org.springframework.jdbc.core.JdbcTemplate;

/** Consumer-side dedupe: records a message id inside the handling transaction; redeliveries are skipped. */
public final class Inbox {

    private final JdbcTemplate jdbc;
    private final String table;

    public Inbox(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        this.table = schema + ".inbox";
    }

    /** {@code true} the first time {@code messageId} is seen. */
    public boolean firstDelivery(String messageId) {
        return jdbc.update("INSERT INTO " + table + " (message_id) VALUES (?) ON CONFLICT DO NOTHING",
                messageId) == 1;
    }
}
