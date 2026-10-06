package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Upstream contracts that account-posting-service (COG-1238) consumes but whose modules have not
 * landed yet. Evaluated last, so each bean disappears as soon as the real module registers one.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class InterimUpstreamAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeePolicy.class)
    FeePolicy interimFeePolicy() {
        return new InterimFeePolicy();
    }

    @Bean
    @ConditionalOnMissingBean(FeeSchedule.class)
    FeeSchedule interimFeeSchedule() {
        return new InterimFeeSchedule();
    }
}
