package com.carddemo.xferfee.feeschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeeSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** How parity-replay sees this module: auto-configuration only, no DataSource. */
class FeeScheduleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FeeScheduleAutoConfiguration.class, FeeScheduleJdbcAutoConfiguration.class));

    @Test
    void replayGetsInMemorySchedule() {
        runner.withPropertyValues("xferfee.replay=true")
                .run(context -> assertThat(context).getBean(FeeSchedule.class)
                        .isInstanceOf(InMemoryFeeSchedule.class));
    }

    @Test
    void noReplayAndNoDatabaseMeansNoSchedule() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FeeSchedule.class));
    }
}
