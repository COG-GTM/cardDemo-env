package com.carddemo.xferfee.recon;

import com.carddemo.xferfee.contracts.Reconciliation;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ReconciliationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Reconciliation reconciliation() {
        return new CobolReconciliation();
    }
}
