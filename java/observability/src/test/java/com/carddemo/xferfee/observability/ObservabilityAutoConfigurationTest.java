package com.carddemo.xferfee.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void providesDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChainObserver.class);
            assertThat(context).hasSingleBean(MeterRegistry.class);
            assertThat(context).hasSingleBean(RecordingAlertPublisher.class);
            assertThat(context).hasSingleBean(DeadLetterQueue.class);
            assertThat(context).hasSingleBean(RunArtifactsWriter.class);
        });
    }

    @Test
    void backsOffForApplicationBeans() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AlertPublisher pager = alert -> { };
        runner.withBean(MeterRegistry.class, () -> registry)
                .withBean(AlertPublisher.class, () -> pager)
                .run(context -> {
                    assertThat(context.getBean(MeterRegistry.class)).isSameAs(registry);
                    assertThat(context.getBean(AlertPublisher.class)).isSameAs(pager);
                    assertThat(context).doesNotHaveBean(RecordingAlertPublisher.class);
                    assertThat(registry.find(XferMetrics.RUN_MAXCC).gauge()).isNotNull();
                });
    }
}
