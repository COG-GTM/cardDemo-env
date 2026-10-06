package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRejected;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transactional outbox. Money is serialised as JSON strings so no precision is lost downstream. */
public class OutboxRepository {

    public static final String TRANSFER_POSTED = "TransferPosted";
    public static final String TRANSFER_REJECTED = "TransferRejected";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(TransferPosted event) {
        insert(event.tranId(), TRANSFER_POSTED, event);
    }

    public void append(TransferRejected event) {
        insert(event.tranId(), TRANSFER_REJECTED, event);
    }

    public List<OutboxEvent> findAll() {
        return jdbc.query("SELECT EVENT_ID, AGGREGATE_ID, EVENT_TYPE, PAYLOAD FROM POSTING_OUTBOX "
                        + "ORDER BY EVENT_ID",
                (rs, row) -> new OutboxEvent(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)));
    }

    private void insert(String aggregateId, String type, Object payload) {
        try {
            jdbc.update("INSERT INTO POSTING_OUTBOX (AGGREGATE_ID, EVENT_TYPE, PAYLOAD) VALUES (?, ?, ?)",
                    aggregateId, type, JSON.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + type + " for " + aggregateId, e);
        }
    }
}
