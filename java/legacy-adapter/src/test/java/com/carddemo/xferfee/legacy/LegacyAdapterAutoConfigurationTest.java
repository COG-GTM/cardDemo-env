package com.carddemo.xferfee.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.legacy.config.LegacyAdapterAutoConfiguration;
import com.carddemo.xferfee.legacy.ingress.InMemoryTransactionPublisher;
import com.carddemo.xferfee.legacy.ingress.KafkaTransactionPublisher;
import com.carddemo.xferfee.legacy.ingress.TransactionPublisher;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class LegacyAdapterAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LegacyAdapterAutoConfiguration.class));

    @Test
    void kafkaTemplatePresentSelectsKafkaPublisher() {
        runner.withBean(KafkaTemplate.class, LegacyAdapterAutoConfigurationTest::template)
                .run(context -> assertThat(context).getBean(TransactionPublisher.class)
                        .isInstanceOf(KafkaTransactionPublisher.class));
    }

    @Test
    void noKafkaTemplateFallsBackToInMemory() {
        runner.run(context -> assertThat(context).getBean(TransactionPublisher.class)
                .isInstanceOf(InMemoryTransactionPublisher.class));
    }

    @Test
    void noSpringKafkaOnClasspathFallsBackToInMemory() {
        runner.withClassLoader(new FilteredClassLoader(KafkaTemplate.class))
                .run(context -> assertThat(context).getBean(TransactionPublisher.class)
                        .isInstanceOf(InMemoryTransactionPublisher.class));
    }

    // never connects: the producer is only created on first send
    private static KafkaTemplate<String, String> template() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }
}
