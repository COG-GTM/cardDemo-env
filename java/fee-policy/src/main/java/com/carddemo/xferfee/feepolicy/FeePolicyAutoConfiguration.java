package com.carddemo.xferfee.feepolicy;

import com.carddemo.xferfee.contracts.FeePolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FeePolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FeePolicy feePolicy() {
        return new CobolFeePolicy();
    }
}
