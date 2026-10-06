package com.carddemo.xferfee.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Default observability wiring. A real deployment supplies its own {@link MeterRegistry}
 * (Prometheus/Datadog via Spring Boot Actuator), {@link AlertPublisher} and {@link DeadLetterQueue};
 * these beans back off when one exists.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"})
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry xferfeeMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public XferMetrics xferMetrics(MeterRegistry registry) {
        return new XferMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(AlertPublisher.class)
    public RecordingAlertPublisher alertPublisher() {
        return new RecordingAlertPublisher(new LoggingAlertPublisher());
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterQueue deadLetterQueue() {
        return new InMemoryDeadLetterQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChainObserver chainObserver(XferMetrics metrics, AlertPublisher alerts, DeadLetterQueue deadLetters,
            ObjectProvider<Clock> clock) {
        return new ChainObserver(metrics, alerts, deadLetters, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    public RunArtifactsWriter runArtifactsWriter() {
        return new RunArtifactsWriter();
    }
}
