package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeSchedule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * In-memory {@link FeeSchedule} for parity replay ({@code xferfee.replay=true}); the database-backed
 * variant is {@link FeeScheduleJdbcAutoConfiguration}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "xferfee.replay", havingValue = "true")
public class FeeScheduleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeeSchedule.class)
    InMemoryFeeSchedule inMemoryFeeSchedule() {
        return new InMemoryFeeSchedule();
    }
}
