package com.carddemo.xferfee.posting;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;

/**
 * Runs this service's own Flyway migrations with a dedicated history table, so other modules'
 * migrations in the same in-process database (parity-replay) never collide on version numbers.
 */
public class AccountPostingSchemaMigrator implements InitializingBean {

    public static final String LOCATION = "classpath:db/migration/account-posting";
    public static final String HISTORY_TABLE = "flyway_history_account_posting";

    private final DataSource dataSource;

    public AccountPostingSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
