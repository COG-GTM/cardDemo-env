package com.carddemo.xferfee.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Declares every chain topic so whichever process starts first creates them. */
public final class XferfeeTopicsConfig {

    private XferfeeTopicsConfig() {
    }

    public static KafkaAdmin.NewTopics newTopics() {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}
