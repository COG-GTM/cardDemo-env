package com.carddemo.xferfee.services.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.events.EventJson;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Account master and XFER_FEE_LEDGER in the posting schema. */
@Component
public class PostingStore {

    private final JdbcTemplate jdbc;

    public PostingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the master for the transaction: one writer at a time, like the exclusive ACCTDATA allocation. */
    public List<Account> lockMaster() {
        return jdbc.query("SELECT payload FROM posting.account ORDER BY seq FOR UPDATE",
                (rs, n) -> EventJson.read(rs.getString(1), Account.class));
    }

    public List<LedgerEntry> ledger() {
        return jdbc.query("SELECT tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, tran_amt, fee_amt, "
                + "cap_applied FROM posting.xfer_fee_ledger ORDER BY tran_id",
                (rs, n) -> new LedgerEntry(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        "Y".equals(rs.getString(8))));
    }

    /** Writes the rows of the new master generation that differ from the one read. */
    public void saveMaster(List<Account> before, List<Account> after) {
        for (int i = 0; i < after.size(); i++) {
            if (i >= before.size() || !after.get(i).equals(before.get(i))) {
                jdbc.update("UPDATE posting.account SET payload = ? WHERE seq = ?",
                        EventJson.write(after.get(i)), i + 1);
            }
        }
    }

    /** Inserts the ledger rows {@code after} has beyond {@code before}; a duplicate TRAN_ID fails the transaction. */
    public void appendLedger(List<LedgerEntry> before, List<LedgerEntry> after) {
        for (LedgerEntry entry : after.subList(before.size(), after.size())) {
            jdbc.update("INSERT INTO posting.xfer_fee_ledger (tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, "
                    + "tran_amt, fee_amt, cap_applied) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    entry.tranId().stripTrailing(), Date.valueOf(entry.tranDate()), entry.sourceAccountId(),
                    entry.targetAccountId(), entry.bookId().stripTrailing(), entry.amount(), entry.feeAmount(),
                    entry.capApplied() ? "Y" : "N");
        }
    }
}
