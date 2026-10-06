package com.carddemo.xfer.kafkasupport;

import com.carddemo.xfer.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class XferKafkaConfig {

    @Bean
    public NewTopic dailyTranTopic() {
        return TopicBuilder.name(Topics.DAILY_TRAN).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic transferRequestedTopic() {
        return TopicBuilder.name(Topics.TRANSFER_REQUESTED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic transferPostedTopic() {
        return TopicBuilder.name(Topics.TRANSFER_POSTED).partitions(1).replicas(1).build();
    }

    /** Redeliver on infrastructure errors (e.g. fee-schedule-service still starting). */
    @Bean
    public DefaultErrorHandler errorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 60L));
    }
}
