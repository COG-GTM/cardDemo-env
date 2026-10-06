package com.carddemo.xferfee.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.TransferIntake;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TransferIntakeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TransferIntakeAutoConfiguration.class));

    @Test
    void exposesTheTransferIntakeBean() {
        runner.run(context -> assertThat(context).getBean(TransferIntake.class)
                .isInstanceOf(TransferIntakeService.class));
    }

    @Test
    void backsOffWhenAnotherTransferIntakeIsDefined() {
        TransferIntake other = (transactions, xrefs, accounts) -> null;
        runner.withBean(TransferIntake.class, () -> other)
                .run(context -> assertThat(context).getBean(TransferIntake.class).isSameAs(other));
    }
}
