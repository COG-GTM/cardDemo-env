package com.carddemo.xferfee.legacy.ingress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Used when no broker is configured (parity replay, tests). */
public final class InMemoryTransactionPublisher implements TransactionPublisher {

    public record Published(String topic, String key, CardTransactionEvent event) {
    }

    private final List<Published> published = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(String topic, String key, CardTransactionEvent event) {
        published.add(new Published(topic, key, event));
    }

    public List<Published> published() {
        synchronized (published) {
            return List.copyOf(published);
        }
    }
}
