package com.carddemo.xferfee.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountPostingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FeeSchedule.class, () -> PostingFixture.SCHEDULE)
            .withBean(FeePolicy.class, () -> PostingFixture.POLICY);

    @Test
    void replayModeExposesAccountPostingWithoutADataSource() {
        runner.withConfiguration(AutoConfigurations.of(AccountPostingAutoConfiguration.class))
                .withPropertyValues("xferfee.replay=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AccountPosting.class);
                    assertThat(context).doesNotHaveBean(TransferPostingRunner.class);
                });
    }

    @Test
    void serviceModeMigratesSchemaAndDefaultsToPerTransfer() {
        runner.withBean(DataSource.class, () -> new DriverManagerDataSource(
                        "jdbc:h2:mem:svc;MODE=PostgreSQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""))
                .withConfiguration(AutoConfigurations.of(DataSourceTransactionManagerAutoConfiguration.class,
                        AccountPostingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TransferPostingRunner.class);
                    assertThat(context).doesNotHaveBean(AccountPosting.class);
                    assertThat(context.getBean(TransferPostingRunner.class).defaultMode())
                            .isEqualTo(PostingMode.PER_TRANSFER);
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA = 'PUBLIC'", String.class))
                            .contains("ACCOUNT", "CARD_XREF", "XFER_FEE_LEDGER", "POSTING_OUTBOX");
                });
    }
}
