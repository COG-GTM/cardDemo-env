package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.AccountPosting;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link AccountPosting} for parity-replay: every call gets a private in-memory H2 database
 * (PostgreSQL mode) migrated with this service's Flyway scripts, so the same repositories, row
 * locks and transaction boundaries as the service are exercised without an application DataSource.
 */
public class InMemoryAccountPosting implements AccountPosting {

    private final FeeSchedule feeSchedule;
    private final FeePolicy feePolicy;
    private final PostingMode mode;

    public InMemoryAccountPosting(FeeSchedule feeSchedule, FeePolicy feePolicy, PostingMode mode) {
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.mode = mode;
    }

    @Override
    public PostingResult post(List<TransferRequested> transfers, List<Account> accountMaster,
            List<LedgerEntry> ledgerBefore) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:posting-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=TRUE", "sa", "");
        try (Connection keepAlive = dataSource.getConnection()) {
            new AccountPostingSchemaMigrator(dataSource).afterPropertiesSet();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
            AccountRepository accounts = new AccountRepository(jdbc);
            FeeLedgerRepository ledger = new FeeLedgerRepository(jdbc);
            OutboxRepository outbox = new OutboxRepository(jdbc);
            AccountMasterService master = new AccountMasterService(accounts, new CardXrefRepository(jdbc),
                    ledger, transactions);
            TransferPostingRunner runner = new TransferPostingRunner(
                    new TransferPostingService(feeSchedule, feePolicy, accounts, ledger, outbox),
                    outbox, transactions, mode);

            master.load(accountMaster, List.of(), ledgerBefore);
            PostingRunResult result = runner.run(transfers, mode);
            return new PostingResult(result.posted(), master.rewrittenMaster(), master.ledger(),
                    result.rejected(), PostingStepReport.of(result));
        } catch (SQLException e) {
            throw new IllegalStateException("in-memory posting database unavailable", e);
        }
    }
}
