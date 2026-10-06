package com.carddemo.xferfee.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingAlertPublisher implements AlertPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingAlertPublisher.class);

    @Override
    public void publish(ChainAlert alert) {
        LOG.error("ALERT {} {} {} RC {}: {}", alert.chain(), alert.step(), alert.program(),
                alert.returnCode(), alert.summary());
    }
}
