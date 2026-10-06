package com.carddemo.xferfee.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every alert (and forwards it) so batch runners and tests can persist or assert on them. */
public class RecordingAlertPublisher implements AlertPublisher {

    private final AlertPublisher delegate;
    private final List<ChainAlert> alerts = new CopyOnWriteArrayList<>();

    public RecordingAlertPublisher(AlertPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(ChainAlert alert) {
        alerts.add(alert);
        delegate.publish(alert);
    }

    public List<ChainAlert> alerts() {
        return List.copyOf(alerts);
    }
}
