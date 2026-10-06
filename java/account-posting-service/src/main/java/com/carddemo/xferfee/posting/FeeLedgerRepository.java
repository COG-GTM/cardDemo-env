package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.LedgerEntry;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class FeeLedgerRepository {

    private final JdbcTemplate jdbc;

    public FeeLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fails with {@link org.springframework.dao.DuplicateKeyException} on a repeated TRAN_ID (BR-14). */
    public void insert(LedgerEntry entry) {
        jdbc.update("INSERT INTO XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, "
                        + "BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                entry.tranId(), Date.valueOf(entry.tranDate()), entry.sourceAccountId(),
                entry.targetAccountId(), entry.bookId(), entry.amount(), entry.feeAmount(),
                entry.capApplied() ? "Y" : "N");
    }

    public List<LedgerEntry> findAll() {
        return jdbc.query("SELECT TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, "
                        + "FEE_AMT, CAP_APPLIED FROM XFER_FEE_LEDGER ORDER BY TRAN_ID",
                (rs, row) -> new LedgerEntry(
                        rs.getString("TRAN_ID").stripTrailing(),
                        rs.getDate("TRAN_DT").toLocalDate(),
                        rs.getLong("SRC_ACCT_ID"),
                        rs.getLong("TGT_ACCT_ID"),
                        rs.getString("BOOK_ID").stripTrailing(),
                        rs.getBigDecimal("TRAN_AMT"),
                        rs.getBigDecimal("FEE_AMT"),
                        "Y".equals(rs.getString("CAP_APPLIED"))));
    }
}
