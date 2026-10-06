package com.carddemo.posting;

import com.carddemo.contracts.TransferPosted;
import com.carddemo.contracts.TransferRejected;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transactional outbox: rows are written in the posting transaction and relayed by a publisher. */
@Repository
public class OutboxRepository {

    public static final String TRANSFER_POSTED = "TransferPosted";
    public static final String TRANSFER_REJECTED = "TransferRejected";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(TransferPosted posted) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("tranId", posted.tranId());
        payload.put("tranDate", posted.tranDate().toString());
        payload.put("sourceAccountId", Long.toString(posted.sourceAccountId()));
        payload.put("targetAccountId", Long.toString(posted.targetAccountId()));
        payload.put("bookId", posted.bookId());
        payload.put("amount", posted.amount().toPlainString());
        payload.put("feePct", posted.feePct().toPlainString());
        payload.put("feeAmount", posted.feeAmount().toPlainString());
        payload.put("capApplied", posted.capAppliedFlag());
        payload.put("ruleEffectiveDate", posted.ruleEffectiveDate().toString());
        insert(posted.tranId(), TRANSFER_POSTED, payload);
    }

    public void append(TransferRejected rejected) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("tranId", rejected.tranId());
        payload.put("reasonCode", rejected.reasonCode());
        payload.put("detail", rejected.detail());
        insert(rejected.tranId(), TRANSFER_REJECTED, payload);
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT EVENT_ID, AGGREGATE_ID, EVENT_TYPE, PAYLOAD FROM POSTING_OUTBOX "
                + "ORDER BY EVENT_ID");
    }

    private void insert(String aggregateId, String eventType, Map<String, String> payload) {
        try {
            jdbc.update("INSERT INTO POSTING_OUTBOX (AGGREGATE_ID, EVENT_TYPE, PAYLOAD) VALUES (?, ?, ?)",
                    aggregateId, eventType, JSON.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
