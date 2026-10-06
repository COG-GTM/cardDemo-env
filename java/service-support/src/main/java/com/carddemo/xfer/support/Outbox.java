package com.carddemo.xfer.support;

import com.carddemo.xfer.contracts.XferEvent;
import com.carddemo.xfer.contracts.XferJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Transactional outbox; outbox-relay publishes rows in id order. */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;

    public Outbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String topic, XferEvent event) {
        jdbc.update("INSERT INTO xfer_java.outbox (run_id, topic, msg_key, payload) "
                + "VALUES (?, ?, ?, ?)", event.runId(), topic, event.runId(), XferJson.write(event));
    }
}
