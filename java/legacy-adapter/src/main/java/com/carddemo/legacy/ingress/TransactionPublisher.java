package com.carddemo.legacy.ingress;

/**
 * Destination for daily transactions read from the legacy extract. The Kafka-backed implementation
 * publishing to {@link #TOPIC} lives with the service wiring; this module stays transport-free.
 */
@FunctionalInterface
public interface TransactionPublisher {
  String TOPIC = "card.transactions";

  /** Publishes one transaction keyed by {@link DailyTransaction#tranId()}. */
  void publish(DailyTransaction transaction);
}
