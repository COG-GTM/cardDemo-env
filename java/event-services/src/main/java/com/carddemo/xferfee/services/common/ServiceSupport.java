package com.carddemo.xferfee.services.common;

import com.carddemo.xferfee.events.Inbox;
import com.carddemo.xferfee.events.Outbox;
import com.carddemo.xferfee.events.XferfeeTopicsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/** Outbox/inbox bound to the service's own schema, plus the chain topics. */
@Configuration(proxyBeanMethods = false)
public class ServiceSupport {

    @Bean
    Outbox outbox(JdbcTemplate jdbc, @Value("${xferfee.schema}") String schema) {
        return new Outbox(jdbc, schema);
    }

    @Bean
    Inbox inbox(JdbcTemplate jdbc, @Value("${xferfee.schema}") String schema) {
        return new Inbox(jdbc, schema);
    }

    @Bean
    RunTables runTables(JdbcTemplate jdbc, @Value("${xferfee.schema}") String schema) {
        return new RunTables(jdbc, schema);
    }

    @Bean
    KafkaAdmin.NewTopics xferfeeTopics() {
        return XferfeeTopicsConfig.newTopics();
    }
}
