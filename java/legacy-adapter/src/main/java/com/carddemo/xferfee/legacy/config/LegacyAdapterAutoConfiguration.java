package com.carddemo.xferfee.legacy.config;

import com.carddemo.xferfee.legacy.egress.LegacyEgress;
import com.carddemo.xferfee.legacy.ingress.InMemoryTransactionPublisher;
import com.carddemo.xferfee.legacy.ingress.KafkaTransactionPublisher;
import com.carddemo.xferfee.legacy.ingress.LegacyFileIngress;
import com.carddemo.xferfee.legacy.ingress.TransactionPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@EnableConfigurationProperties(LegacyAdapterProperties.class)
public class LegacyAdapterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LegacyFileIngress legacyFileIngress(LegacyAdapterProperties properties) {
        return new LegacyFileIngress(properties.codecOptions());
    }

    @Bean
    @ConditionalOnMissingBean
    LegacyEgress legacyEgress(LegacyAdapterProperties properties) {
        return new LegacyEgress(properties.codecOptions());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    static class KafkaPublisherConfiguration {

        @Bean
        @ConditionalOnBean(KafkaTemplate.class)
        @ConditionalOnMissingBean(TransactionPublisher.class)
        TransactionPublisher kafkaTransactionPublisher(KafkaTemplate<String, String> kafka) {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return new KafkaTransactionPublisher(kafka, mapper);
        }
    }

    @Bean
    @ConditionalOnMissingBean(TransactionPublisher.class)
    TransactionPublisher inMemoryTransactionPublisher() {
        return new InMemoryTransactionPublisher();
    }
}
