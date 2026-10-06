package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(after = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
@EnableConfigurationProperties(AccountPostingProperties.class)
public class AccountPostingAutoConfiguration {

    /** parity-replay ({@code xferfee.replay=true}): the STEP020 contract over a private in-memory DB. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "xferfee.replay", havingValue = "true")
    static class Replay {

        @Bean
        @ConditionalOnMissingBean(AccountPosting.class)
        AccountPosting inMemoryAccountPosting(ObjectProvider<FeeSchedule> feeSchedule,
                ObjectProvider<FeePolicy> feePolicy, AccountPostingProperties properties) {
            return new LazyAccountPosting(feeSchedule, feePolicy, properties.replayMode());
        }
    }

    /** Service runtime: the application's DataSource, schema owned via Flyway. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "xferfee.replay", havingValue = "false", matchIfMissing = true)
    @ConditionalOnBean(DataSource.class)
    static class Service {

        @Bean
        AccountPostingSchemaMigrator accountPostingSchemaMigrator(DataSource dataSource) {
            return new AccountPostingSchemaMigrator(dataSource);
        }

        @Bean
        @DependsOn("accountPostingSchemaMigrator")
        AccountRepository accountRepository(DataSource dataSource) {
            return new AccountRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @DependsOn("accountPostingSchemaMigrator")
        CardXrefRepository cardXrefRepository(DataSource dataSource) {
            return new CardXrefRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @DependsOn("accountPostingSchemaMigrator")
        FeeLedgerRepository feeLedgerRepository(DataSource dataSource) {
            return new FeeLedgerRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @DependsOn("accountPostingSchemaMigrator")
        OutboxRepository outboxRepository(DataSource dataSource) {
            return new OutboxRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        TransferPostingService transferPostingService(FeeSchedule feeSchedule, FeePolicy feePolicy,
                AccountRepository accounts, FeeLedgerRepository ledger, OutboxRepository outbox) {
            return new TransferPostingService(feeSchedule, feePolicy, accounts, ledger, outbox);
        }

        @Bean
        TransferPostingRunner transferPostingRunner(TransferPostingService service, OutboxRepository outbox,
                ObjectProvider<PlatformTransactionManager> transactionManager, DataSource dataSource,
                AccountPostingProperties properties) {
            return new TransferPostingRunner(service, outbox,
                    transactionManager.getIfAvailable(() -> new DataSourceTransactionManager(dataSource)),
                    properties.mode());
        }

        @Bean
        AccountMasterService accountMasterService(AccountRepository accounts, CardXrefRepository xrefs,
                FeeLedgerRepository ledger, ObjectProvider<PlatformTransactionManager> transactionManager,
                DataSource dataSource) {
            return new AccountMasterService(accounts, xrefs, ledger,
                    transactionManager.getIfAvailable(() -> new DataSourceTransactionManager(dataSource)));
        }
    }
}
