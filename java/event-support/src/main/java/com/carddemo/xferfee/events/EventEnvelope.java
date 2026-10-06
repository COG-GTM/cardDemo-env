package com.carddemo.xferfee.events;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire format of every xferfee Kafka record (value is this JSON, key is {@code runId} so one run
 * stays on one partition and keeps file order). {@code messageId} is the consumer dedupe key.
 */
public record EventEnvelope(
        String messageId,
        String runId,
        String type,
        RunMode mode,
        long seq,
        JsonNode payload) {
}
