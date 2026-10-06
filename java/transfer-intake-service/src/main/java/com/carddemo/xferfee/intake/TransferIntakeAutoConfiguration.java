package com.carddemo.xferfee.intake;

import com.carddemo.xferfee.contracts.TransferIntake;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class TransferIntakeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransferIntake.class)
    TransferIntake transferIntake() {
        return new TransferIntakeService();
    }
}
