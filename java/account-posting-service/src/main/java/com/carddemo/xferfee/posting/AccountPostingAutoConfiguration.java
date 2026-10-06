package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = {
    "com.carddemo.xferfee.feepolicy.FeePolicyAutoConfiguration",
    "com.carddemo.xferfee.feeschedule.FeeScheduleAutoConfiguration"})
public class AccountPostingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({FeeSchedule.class, FeePolicy.class})
    AccountPosting accountPosting(FeeSchedule feeSchedule, FeePolicy feePolicy) {
        return new CobolAccountPosting(feeSchedule, feePolicy);
    }
}
