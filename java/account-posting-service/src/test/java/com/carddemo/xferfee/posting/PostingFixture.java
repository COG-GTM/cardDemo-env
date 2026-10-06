package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** A migrated in-memory database with the posting components wired as in production. */
final class PostingFixture implements AutoCloseable {

    static final FeeRule RETAIL = new FeeRule("RETAIL", new BigDecimal("0.015000"), new BigDecimal("25.00"),
            LocalDate.of(2024, 6, 15), LocalDate.of(9999, 12, 31));
    static final FeeRule INSTL = new FeeRule("INSTL", new BigDecimal("0.005000"), new BigDecimal("500.00"),
            LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31));

    /** Test double for the COG-1235 policy: half-up to the cent, then cap when strictly greater. */
    static final FeePolicy POLICY = (amount, rule) -> {
        BigDecimal fee = amount.multiply(rule.feePct()).setScale(2, RoundingMode.HALF_UP);
        return fee.compareTo(rule.feeCap()) > 0 ? new FeeResult(rule.feeCap(), true) : new FeeResult(fee, false);
    };

    static final FeeSchedule SCHEDULE = new FeeSchedule() {
        @Override
        public void seed(List<FeeRule> rules) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FeeRule> effectiveRule(String bookId, LocalDate businessDate) {
            return rules().stream()
                    .filter(r -> r.bookId().equals(bookId))
                    .filter(r -> !r.effectiveDate().isAfter(businessDate) && r.expiryDate().isAfter(businessDate))
                    .findFirst();
        }

        @Override
        public List<FeeRule> rules() {
            return List.of(INSTL, RETAIL);
        }
    };

    final SingleConnectionDataSource dataSource;
    final JdbcTemplate jdbc;
    final AccountRepository accounts;
    final FeeLedgerRepository ledger;
    final OutboxRepository outbox;
    final AccountMasterService master;
    final TransferPostingService service;
    final TransferPostingRunner runner;

    PostingFixture(PostingMode mode) {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:test-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=TRUE", "sa", "", true);
        new AccountPostingSchemaMigrator(dataSource).afterPropertiesSet();
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        accounts = new AccountRepository(jdbc);
        ledger = new FeeLedgerRepository(jdbc);
        outbox = new OutboxRepository(jdbc);
        master = new AccountMasterService(accounts, new CardXrefRepository(jdbc), ledger, tx);
        service = new TransferPostingService(SCHEDULE, POLICY, accounts, ledger, outbox);
        runner = new TransferPostingRunner(service, outbox, tx, mode);
    }

    void load(List<Account> master, List<LedgerEntry> ledgerRows) {
        this.master.load(master, List.of(), ledgerRows);
    }

    Account account(long id) {
        return master.rewrittenMaster().stream().filter(a -> a.accountId() == id).findFirst().orElseThrow();
    }

    static Account account(long id, String status, String balance, String creditLimit) {
        return new Account(id, status, new BigDecimal(balance), new BigDecimal(creditLimit), new BigDecimal("100.00"),
                "2020-01-01", "2030-12-31", "2025-01-01", new BigDecimal("0.00"), new BigDecimal("0.00"),
                "00001", "RETAIL");
    }

    static TransferRequested transfer(String tranId, long source, long target, String book, String amount) {
        return new TransferRequested(tranId, LocalDate.of(2024, 6, 20), source, target, book,
                new BigDecimal(amount), "1000000000000001");
    }

    @Override
    public void close() {
        dataSource.destroy();
    }
}
