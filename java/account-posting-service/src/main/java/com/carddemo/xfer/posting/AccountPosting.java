package com.carddemo.xfer.posting;

import com.carddemo.xfer.contracts.Account;
import com.carddemo.xfer.contracts.BatchPosted;
import com.carddemo.xfer.contracts.ExtractClosed;
import com.carddemo.xfer.contracts.FeePolicy;
import com.carddemo.xfer.contracts.FeeResult;
import com.carddemo.xfer.contracts.FeeRule;
import com.carddemo.xfer.contracts.Topics;
import com.carddemo.xfer.contracts.TransferPosted;
import com.carddemo.xfer.contracts.TransferRejected;
import com.carddemo.xfer.contracts.TransferRequested;
import com.carddemo.xfer.contracts.XferEvent;
import com.carddemo.xfer.contracts.XferJson;
import com.carddemo.xfer.support.Outbox;
import com.carddemo.xfer.support.RunLog;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** XFERFEE: price each transfer, post balances, write fee records and the ledger. */
@Component
public class AccountPosting {

    static final String STEP = "STEP020";
    static final int TABLE_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RunLog runLog;
    private final Outbox outbox;
    private final FeeScheduleClient feeSchedule;
    private final FeePolicy feePolicy;
    private final PostingMode mode;

    public AccountPosting(JdbcTemplate jdbc, TransactionTemplate tx, RunLog runLog, Outbox outbox,
                          FeeScheduleClient feeSchedule, FeePolicy feePolicy,
                          @Value("${xfer.posting.mode}") String mode) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.runLog = runLog;
        this.outbox = outbox;
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.mode = PostingMode.parse(mode);
    }

    @KafkaListener(topics = Topics.TRANSFER_REQUESTED, groupId = "account-posting")
    public void onMessage(String payload) {
        XferEvent event = XferJson.read(payload);
        if (runLog.stepRc(event.runId(), STEP).isPresent()) {
            return;
        }
        switch (event) {
            case TransferRequested request when mode == PostingMode.BATCH_ATOMIC ->
                    jdbc.update("INSERT INTO xfer_java.posting_inbox (run_id, seq, payload) "
                            + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                            request.runId(), request.seq(), payload);
            case TransferRequested request -> postSingle(request);
            case ExtractClosed closed -> close(closed);
            default -> {
            }
        }
    }

    private void close(ExtractClosed closed) {
        if (mode == PostingMode.BATCH_ATOMIC) {
            // runjcl stops the chain on any non-zero step RC.
            if (closed.rc() != 0) {
                return;
            }
            postBatch(closed.runId());
        } else {
            finishPerTransfer(closed.runId());
        }
    }

    /** All transfers of the run commit together, or none do (one COMMIT at end of XFERFEE). */
    private void postBatch(String runId) {
        try {
            tx.executeWithoutResult(status -> {
                List<TransferRequested> requests = jdbc.queryForList(
                                "SELECT payload FROM xfer_java.posting_inbox WHERE run_id = ? ORDER BY seq",
                                String.class, runId).stream()
                        .map(p -> (TransferRequested) XferJson.read(p))
                        .toList();
                List<Account> accounts = loadAccounts();
                BigDecimal feeTotal = BigDecimal.ZERO.setScale(2);
                for (TransferRequested request : requests) {
                    feeTotal = feeTotal.add(postOne(request, accounts).feeAmt());
                }
                saveAccounts(accounts);
                finishStep(runId, requests.size(), feeTotal, 0);
            });
        } catch (PostingAbend abend) {
            tx.executeWithoutResult(status -> {
                runLog.sysout(runId, STEP, abend.getMessage());
                runLog.sysout(runId, STEP, "XFERFEE: 9999-ABEND-PROGRAM");
                runLog.stepDone(runId, STEP, 8);
                outbox.append(Topics.TRANSFER_POSTED,
                        new BatchPosted(runId, 0, BigDecimal.ZERO.setScale(2), 8));
            });
        }
    }

    /** Per-transfer mode: each transfer commits alone; failures are parked, the run continues. */
    private void postSingle(TransferRequested request) {
        Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM xfer_java.fee_record "
                + "WHERE run_id = ? AND seq = ?", Integer.class, request.runId(), request.seq());
        if (done != null && done > 0) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                List<Account> accounts = loadAccounts();
                postOne(request, accounts);
                saveAccounts(accounts);
            });
        } catch (PostingAbend rejected) {
            tx.executeWithoutResult(status -> {
                runLog.sysout(request.runId(), STEP, rejected.getMessage());
                outbox.append(Topics.TRANSFER_POSTED, new TransferRejected(
                        request.runId(), request.seq(), request.tranId(), rejected.getMessage()));
            });
        }
    }

    private void finishPerTransfer(String runId) {
        tx.executeWithoutResult(status -> {
            Long posted = jdbc.queryForObject("SELECT COUNT(*) FROM xfer_java.fee_record "
                    + "WHERE run_id = ?", Long.class, runId);
            BigDecimal feeTotal = jdbc.queryForObject("SELECT COALESCE(SUM(fee_amt), 0) "
                    + "FROM xfer_java.fee_record WHERE run_id = ?", BigDecimal.class, runId);
            Long rejected = jdbc.queryForObject("SELECT COUNT(*) FROM xfer_java.outbox "
                    + "WHERE run_id = ? AND payload LIKE '{\"type\":\"TransferRejected\"%'",
                    Long.class, runId);
            finishStep(runId, posted, feeTotal.setScale(2), rejected > 0 ? 4 : 0);
        });
    }

    private void finishStep(String runId, long posted, BigDecimal feeTotal, int rc) {
        runLog.sysout(runId, STEP, "XFERFEE: TRANSFERS POSTED " + String.format("%09d", posted));
        runLog.sysout(runId, STEP, "XFERFEE: TOTAL FEES " + displaySigned(feeTotal));
        runLog.stepDone(runId, STEP, rc);
        outbox.append(Topics.TRANSFER_POSTED, new BatchPosted(runId, posted, feeTotal, rc));
    }

    /** 2100-POST-ONE. */
    private TransferPosted postOne(TransferRequested request, List<Account> accounts) {
        FeeRule rule = feeSchedule.effectiveRule(request.bookId(), request.tranDt())
                .orElseThrow(() -> new PostingAbend("XFERFEE: NO FEE RULE FOR BOOK " + request.bookId()));
        FeeResult fee = feePolicy.apply(request.tranAmt(), rule);
        int src = lastIndexOf(accounts, request.srcAcctId());
        int tgt = lastIndexOf(accounts, request.tgtAcctId());
        if (src < 0 || tgt < 0) {
            throw new PostingAbend("XFERFEE: ACCOUNT NOT FOUND "
                    + String.format("%011d", request.srcAcctId()) + " / "
                    + String.format("%011d", request.tgtAcctId()));
        }
        BigDecimal debit = request.tranAmt().add(fee.feeAmt());
        Account source = accounts.get(src);
        accounts.set(src, source.withPosting(source.currBal().subtract(debit),
                source.currCycCredit(), source.currCycDebit().add(debit)));
        Account target = accounts.get(tgt);
        accounts.set(tgt, target.withPosting(target.currBal().add(request.tranAmt()),
                target.currCycCredit().add(request.tranAmt()), target.currCycDebit()));
        TransferPosted posted = new TransferPosted(request.runId(), request.seq(), request.tranId(),
                request.tranDt(), request.srcAcctId(), request.tgtAcctId(), request.bookId(),
                request.tranAmt(), rule.feePct(), fee.feeAmt(), fee.capApplied(),
                rule.effDt().toString());
        jdbc.update("INSERT INTO xfer_java.fee_record (run_id, seq, tran_id, tran_dt, src_acct_id, "
                        + "tgt_acct_id, book_id, tran_amt, fee_pct, fee_amt, cap_applied, rule_eff_dt) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                posted.runId(), posted.seq(), posted.tranId(), posted.tranDt(), posted.srcAcctId(),
                posted.tgtAcctId(), posted.bookId(), posted.tranAmt(), posted.feePct(),
                posted.feeAmt(), posted.capApplied() ? "Y" : "N", posted.ruleEffDt());
        try {
            jdbc.update("INSERT INTO XFER_FEE_LEDGER (TRAN_ID, TRAN_DT, SRC_ACCT_ID, TGT_ACCT_ID, "
                            + "BOOK_ID, TRAN_AMT, FEE_AMT, CAP_APPLIED) "
                            + "VALUES (?, CAST(? AS DATE), ?, ?, ?, ?, ?, ?)",
                    posted.tranId(), posted.tranDt(), posted.srcAcctId(), posted.tgtAcctId(),
                    posted.bookId(), posted.tranAmt(), posted.feeAmt(),
                    posted.capApplied() ? "Y" : "N");
        } catch (DuplicateKeyException e) {
            throw new PostingAbend("XFERFEE: LEDGER INSERT FAILED " + posted.tranId());
        }
        outbox.append(Topics.TRANSFER_POSTED, posted);
        return posted;
    }

    /** 2200-FIND-ACCOUNTS scans the whole table, so the last duplicate wins. */
    private static int lastIndexOf(List<Account> accounts, long acctId) {
        for (int i = accounts.size() - 1; i >= 0; i--) {
            if (accounts.get(i).acctId() == acctId) {
                return i;
            }
        }
        return -1;
    }

    private List<Account> loadAccounts() {
        return new java.util.ArrayList<>(jdbc.query(
                "SELECT acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, "
                        + "expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, "
                        + "group_id FROM xfer_java.account WHERE seq <= ? ORDER BY seq FOR UPDATE",
                (rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11),
                        rs.getString(12)),
                TABLE_LIMIT));
    }

    private void saveAccounts(List<Account> accounts) {
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            jdbc.update("UPDATE xfer_java.account SET curr_bal = ?, curr_cyc_credit = ?, "
                    + "curr_cyc_debit = ? WHERE seq = ?",
                    a.currBal(), a.currCycCredit(), a.currCycDebit(), i + 1);
        }
    }

    private static String displaySigned(BigDecimal value) {
        java.math.BigInteger units = value.setScale(2).unscaledValue();
        return (units.signum() < 0 ? "-" : "+") + String.format("%011d", units.abs());
    }
}
