package com.carddemo.xferfee.feepolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeePolicyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeePolicyAutoConfiguration.class));

    @Test
    void exposesHalfUpFeePolicyBean() {
        runner.run(context -> assertThat(context).getBean(FeePolicy.class).isInstanceOf(HalfUpFeePolicy.class));
    }

    @Test
    void backsOffWhenAnotherFeePolicyIsDefined() {
        FeePolicy custom = (amount, rule) -> new FeeResult(BigDecimal.ONE, false);
        runner.withBean(FeePolicy.class, () -> custom)
                .run(context -> assertThat(context).getBean(FeePolicy.class).isSameAs(custom));
    }
}
