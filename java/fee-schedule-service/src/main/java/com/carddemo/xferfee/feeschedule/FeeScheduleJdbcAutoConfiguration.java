package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeSchedule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link FeeSchedule} over the {@code fee_rule} table, outside parity replay. */
@AutoConfiguration(after = JdbcClientAutoConfiguration.class)
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnBean(JdbcClient.class)
@ConditionalOnProperty(name = "xferfee.replay", havingValue = "false", matchIfMissing = true)
public class FeeScheduleJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FeeRuleRepository feeRuleRepository(JdbcClient jdbc) {
        return new FeeRuleRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(FeeSchedule.class)
    JdbcFeeSchedule jdbcFeeSchedule(FeeRuleRepository repository) {
        return new JdbcFeeSchedule(repository);
    }
}
