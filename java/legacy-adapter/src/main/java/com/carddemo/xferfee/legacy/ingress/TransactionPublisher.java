package com.carddemo.xferfee.legacy.ingress;

@FunctionalInterface
public interface TransactionPublisher {

    void publish(String topic, String key, CardTransactionEvent event);
}
