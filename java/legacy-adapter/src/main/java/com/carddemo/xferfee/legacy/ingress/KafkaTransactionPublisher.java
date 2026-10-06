package com.carddemo.xferfee.legacy.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

/** Publishes JSON events and waits for the broker ack so ingress never reports unsent records. */
public final class KafkaTransactionPublisher implements TransactionPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaTransactionPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void publish(String topic, String key, CardTransactionEvent event) {
        try {
            kafka.send(topic, key, mapper.writeValueAsString(event)).join();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + event, e);
        }
    }
}
