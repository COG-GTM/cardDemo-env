package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeePolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Exposes {@link HalfUpFeePolicy} as the {@link FeePolicy} bean (picked up by parity-replay and services). */
@AutoConfiguration
public class FeePolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeePolicy.class)
    public FeePolicy feePolicy() {
        return new HalfUpFeePolicy();
    }
}
