package com.carddemo.posting;

import com.carddemo.contracts.TransferPosted;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeeLedgerRepository {

    private final JdbcTemplate jdbc;

    public FeeLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per TRAN_ID; a duplicate violates the primary key. */
    public void insert(TransferPosted posted) {
        jdbc.update("INSERT INTO XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, "
                        + "FEE_AMT, CAP_APPLIED) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                posted.tranId(), Date.valueOf(posted.tranDate()), posted.sourceAccountId(),
                posted.targetAccountId(), posted.bookId(), posted.amount(), posted.feeAmount(),
                posted.capAppliedFlag());
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, BOOK_ID, TRAN_AMT, FEE_AMT, "
                + "CAP_APPLIED FROM XFER_FEE_LEDGER ORDER BY TRAN_ID");
    }
}
