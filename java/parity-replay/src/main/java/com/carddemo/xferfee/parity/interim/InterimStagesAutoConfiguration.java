package com.carddemo.xferfee.parity.interim;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.Reconciliation;
import com.carddemo.xferfee.contracts.TransferIntake;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * COBOL-faithful stand-ins for the step modules (COG-1235..COG-1239) so the legacy-adapter
 * codec can be parity-gated end to end before those land. Ordered last and
 * {@code @ConditionalOnMissingBean}: each one backs off as soon as the real module registers
 * its contract bean. {@code --xferfee.interim-stages=false} turns them all off.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "xferfee.interim-stages", havingValue = "true", matchIfMissing = true)
public class InterimStagesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FeePolicy interimFeePolicy() {
        return new InterimFeePolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    FeeSchedule interimFeeSchedule() {
        return new InterimFeeSchedule();
    }

    @Bean
    @ConditionalOnMissingBean
    TransferIntake interimTransferIntake() {
        return new InterimTransferIntake();
    }

    @Bean
    @ConditionalOnMissingBean
    AccountPosting interimAccountPosting(FeeSchedule schedule, FeePolicy policy) {
        return new InterimAccountPosting(schedule, policy);
    }

    @Bean
    @ConditionalOnMissingBean
    Reconciliation interimReconciliation() {
        return new InterimReconciliation();
    }
}
