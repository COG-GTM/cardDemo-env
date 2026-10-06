package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

public class AccountRepository {

    private static final String COLUMNS = "ACCT_ID, ACTIVE_STATUS, CURR_BAL, CREDIT_LIMIT, "
            + "CASH_CREDIT_LIMIT, OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE, CURR_CYC_CREDIT, "
            + "CURR_CYC_DEBIT, ADDR_ZIP, GROUP_ID";

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account, int loadSeq) {
        jdbc.update("INSERT INTO ACCOUNT (" + COLUMNS + ", LOAD_SEQ) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                account.accountId(), account.activeStatus(), account.currentBalance(),
                account.creditLimit(), account.cashCreditLimit(), account.openDate(),
                account.expirationDate(), account.reissueDate(), account.currentCycleCredit(),
                account.currentCycleDebit(), account.addressZip(), account.groupId(), loadSeq);
    }

    /** Locks the given accounts in ascending id order and returns the ones that exist. */
    public Map<Long, Account> lockForUpdate(Collection<Long> ids) {
        List<Long> sorted = ids.stream().distinct().sorted().toList();
        String placeholders = sorted.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Account> rows = jdbc.query("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCT_ID IN ("
                        + placeholders + ") ORDER BY ACCT_ID FOR UPDATE",
                AccountRepository::map, sorted.toArray());
        Map<Long, Account> byId = new LinkedHashMap<>();
        rows.forEach(account -> byId.put(account.accountId(), account));
        return byId;
    }

    public void updateBalances(long id, BigDecimal balance, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        jdbc.update("UPDATE ACCOUNT SET CURR_BAL = ?, CURR_CYC_CREDIT = ?, CURR_CYC_DEBIT = ? "
                + "WHERE ACCT_ID = ?", balance, cycleCredit, cycleDebit, id);
    }

    public List<Account> findAllInLoadOrder() {
        return jdbc.query("SELECT " + COLUMNS + " FROM ACCOUNT ORDER BY LOAD_SEQ", AccountRepository::map);
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getLong("ACCT_ID"),
                rs.getString("ACTIVE_STATUS"),
                rs.getBigDecimal("CURR_BAL"),
                rs.getBigDecimal("CREDIT_LIMIT"),
                rs.getBigDecimal("CASH_CREDIT_LIMIT"),
                rs.getString("OPEN_DATE"),
                rs.getString("EXPIRATION_DATE"),
                rs.getString("REISSUE_DATE"),
                rs.getBigDecimal("CURR_CYC_CREDIT"),
                rs.getBigDecimal("CURR_CYC_DEBIT"),
                rs.getString("ADDR_ZIP"),
                rs.getString("GROUP_ID"));
    }
}
