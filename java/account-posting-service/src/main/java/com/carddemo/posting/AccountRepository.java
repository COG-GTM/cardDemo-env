package com.carddemo.posting;

import com.carddemo.contracts.Account;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "ACCT_ID, ACTIVE_STATUS, CURR_BAL, CREDIT_LIMIT, CASH_CREDIT_LIMIT, "
            + "OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE, CURR_CYC_CREDIT, CURR_CYC_DEBIT, ADDR_ZIP, GROUP_ID";

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        jdbc.update("INSERT INTO ACCOUNT (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                account.acctId(), account.activeStatus(), account.currBal(), account.creditLimit(),
                account.cashCreditLimit(), account.openDate(), account.expirationDate(), account.reissueDate(),
                account.currCycCredit(), account.currCycDebit(), account.addrZip(), account.groupId());
    }

    public Optional<Account> lock(long acctId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCT_ID = ? FOR UPDATE",
                AccountRepository::map, acctId).stream().findFirst();
    }

    public Optional<Account> find(long acctId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCT_ID = ?",
                AccountRepository::map, acctId).stream().findFirst();
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM ACCOUNT ORDER BY ACCT_ID", AccountRepository::map);
    }

    /** Source side: balance -= amount + fee, cycle debit += amount + fee. */
    public void debitSource(long acctId, BigDecimal amount, BigDecimal fee) {
        jdbc.update("UPDATE ACCOUNT SET CURR_BAL = CURR_BAL - ? - ?, CURR_CYC_DEBIT = CURR_CYC_DEBIT + ? + ? "
                + "WHERE ACCT_ID = ?", amount, fee, amount, fee, acctId);
    }

    /** Target side: balance += amount, cycle credit += amount. */
    public void creditTarget(long acctId, BigDecimal amount) {
        jdbc.update("UPDATE ACCOUNT SET CURR_BAL = CURR_BAL + ?, CURR_CYC_CREDIT = CURR_CYC_CREDIT + ? "
                + "WHERE ACCT_ID = ?", amount, amount, acctId);
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getLong("ACCT_ID"), rs.getString("ACTIVE_STATUS"), rs.getBigDecimal("CURR_BAL"),
                rs.getBigDecimal("CREDIT_LIMIT"), rs.getBigDecimal("CASH_CREDIT_LIMIT"), rs.getString("OPEN_DATE"),
                rs.getString("EXPIRATION_DATE"), rs.getString("REISSUE_DATE"), rs.getBigDecimal("CURR_CYC_CREDIT"),
                rs.getBigDecimal("CURR_CYC_DEBIT"), rs.getString("ADDR_ZIP"), rs.getString("GROUP_ID"));
    }
}
