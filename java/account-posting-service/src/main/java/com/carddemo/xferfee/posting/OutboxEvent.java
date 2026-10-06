package com.carddemo.xferfee.posting;

public record OutboxEvent(long eventId, String aggregateId, String eventType, String payload) {
}
