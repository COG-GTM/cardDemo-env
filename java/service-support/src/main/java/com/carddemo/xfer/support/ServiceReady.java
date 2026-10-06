package com.carddemo.xfer.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Lets parity-replay know every service process is up before it publishes a run. */
@Component
public class ServiceReady {

    private final JdbcTemplate jdbc;
    private final String service;

    public ServiceReady(JdbcTemplate jdbc, @Value("${spring.application.name}") String service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        jdbc.update("INSERT INTO xfer_java.service_ready (service, started_at) "
                + "VALUES (?, CURRENT_TIMESTAMP) ON CONFLICT (service) "
                + "DO UPDATE SET started_at = EXCLUDED.started_at", service);
    }
}
