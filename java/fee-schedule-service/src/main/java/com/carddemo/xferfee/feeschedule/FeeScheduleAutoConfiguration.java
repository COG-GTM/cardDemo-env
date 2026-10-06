package com.carddemo.xferfee.feeschedule;

import com.carddemo.xferfee.contracts.FeeSchedule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** In-memory schedule for the replay path; the service process provides a JDBC-backed bean instead. */
@AutoConfiguration
public class FeeScheduleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FeeSchedule feeSchedule() {
        return new InMemoryFeeSchedule();
    }
}
