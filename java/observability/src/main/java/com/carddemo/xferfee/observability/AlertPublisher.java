package com.carddemo.xferfee.observability;

public interface AlertPublisher {

    void publish(ChainAlert alert);
}
