package com.carddemo.xferfee.live.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfer-fee engine over its own Postgres schema ({@code java_engine}). Implements the
 * CBXFR01C selection rules and the XFERFEE posting rules for one daily transaction at a time.
 */
@Service
public class JdbcTransferFeeEngine implements TransferFeeEngine {

    static final String TRANSFER_TYPE = "08";

    private final JdbcTemplate jdbc;
    private final AtomicReference<RoundingMode> rounding;

    public JdbcTransferFeeEngine(JdbcTemplate jdbc, @Value("${xferfee.rounding:HALF_UP}") RoundingMode rounding) {
        this.jdbc = jdbc;
        this.rounding = new AtomicReference<>(rounding);
    }

    @Override
    @Transactional
    public void reset(EngineSeed seed) {
        jdbc.update("TRUNCATE java_engine.xfer_fee_ledger, java_engine.account, "
                + "java_engine.card_xref, java_engine.ctl_xfer_parm");
        for (Account a : seed.accounts()) {
            jdbc.update("INSERT INTO java_engine.account (acct_id, active_status, curr_bal, credit_limit, "
                    + "cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, "
                    + "curr_cyc_debit, addr_zip, group_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    a.acctId(), a.activeStatus(), a.currBal(), a.creditLimit(), a.cashCreditLimit(),
                    a.openDate(), a.expirationDate(), a.reissueDate(), a.currCycCredit(), a.currCycDebit(),
                    a.addrZip(), a.groupId().strip());
        }
        for (CardXref x : seed.xref()) {
            jdbc.update("INSERT INTO java_engine.card_xref (card_num, cust_id, acct_id) VALUES (?,?,?)",
                    x.cardNum(), x.custId(), x.acctId());
        }
        for (FeeRule r : seed.rules()) {
            jdbc.update("INSERT INTO java_engine.ctl_xfer_parm (book_id, fee_pct, fee_cap, eff_dt, exp_dt) "
                    + "VALUES (?,?,?,?,?)",
                    r.bookId().strip(), r.feePct(), r.feeCap(), Date.valueOf(r.effDt()), Date.valueOf(r.expDt()));
        }
    }

    @Override
    @Transactional
    public TransferResult process(DailyTransaction tx) {
        String mode = rounding.get().name();
        // BR-01: only type 08 is a transfer.
        if (!TRANSFER_TYPE.equals(tx.typeCd())) {
            return TransferResult.notPosted(tx, TransferOutcome.IGNORED, "BR-01",
                    "TRAN-TYPE-CD " + tx.typeCd() + " is not a transfer", mode);
        }
        // BR-02: source account from the card cross-reference.
        Optional<Long> sourceId = jdbc.query(
                "SELECT acct_id FROM java_engine.card_xref WHERE card_num = ?",
                (rs, i) -> rs.getLong(1), tx.cardNum()).stream().findFirst();
        if (sourceId.isEmpty()) {
            return TransferResult.notPosted(tx, TransferOutcome.SKIPPED, "BR-05",
                    "card " + tx.cardNum() + " not in cross-reference", mode);
        }
        Optional<Account> source = findAccount(sourceId.get());
        if (source.isEmpty()) {
            return TransferResult.notPosted(tx, TransferOutcome.SKIPPED, "BR-05",
                    "source account " + sourceId.get() + " not in account master", mode);
        }
        // BR-03 / BR-04: book from the source account; target from TRAN-DESC(14:11); date from TRAN-ORIG-TS(1:10).
        String book = source.get().groupId().strip();
        String businessDate = tx.origTs().substring(0, 10);
        Long targetId = targetAccountId(tx.desc());

        // BR-06 / BR-07: rule effective on the transaction's own date, half-open.
        LocalDate date = LocalDate.parse(businessDate);
        List<FeeRule> rules = jdbc.query(
                "SELECT book_id, fee_pct, fee_cap, eff_dt, exp_dt FROM java_engine.ctl_xfer_parm "
                        + "WHERE book_id = ? AND eff_dt <= ? AND exp_dt > ?",
                JdbcTransferFeeEngine::mapRule, book, Date.valueOf(date), Date.valueOf(date));
        if (rules.isEmpty()) {
            throw reject(tx, mode, "BR-07", "no fee rule for book " + book + " on " + businessDate);
        }
        FeeRule rule = rules.get(0);

        // BR-08 .. BR-10.
        Fee fee = FeeCalculator.compute(tx.amount(), rule, rounding.get());

        // BR-12: both accounts must exist.
        if (targetId == null || findAccount(targetId).isEmpty()) {
            throw reject(tx, mode, "BR-12", "account not found " + sourceId.get() + " / " + targetId);
        }
        // BR-14: ledger keyed by TRAN_ID.
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM java_engine.xfer_fee_ledger WHERE tran_id = ?", Integer.class, tx.tranId());
        if (dup != null && dup > 0) {
            throw reject(tx, mode, "BR-14", "duplicate TRAN_ID " + tx.tranId());
        }

        // BR-11: fee charged to the source only.
        BigDecimal debit = tx.amount().add(fee.amount());
        jdbc.update("UPDATE java_engine.account SET curr_bal = curr_bal - ?, curr_cyc_debit = curr_cyc_debit + ? "
                + "WHERE acct_id = ?", debit, debit, sourceId.get());
        jdbc.update("UPDATE java_engine.account SET curr_bal = curr_bal + ?, curr_cyc_credit = curr_cyc_credit + ? "
                + "WHERE acct_id = ?", tx.amount(), tx.amount(), targetId);

        LedgerRow ledger = new LedgerRow(tx.tranId(), date, sourceId.get(), targetId, book,
                tx.amount().setScale(2, RoundingMode.UNNECESSARY), rule.feePct(), fee.amount(),
                fee.capApplied() ? "Y" : "N", rule.effDt());
        jdbc.update("INSERT INTO java_engine.xfer_fee_ledger (tran_id, tran_dt, src_acct_id, tgt_acct_id, book_id, "
                + "tran_amt, fee_pct, fee_amt, cap_applied, rule_eff_dt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                ledger.tranId(), Date.valueOf(ledger.tranDt()), ledger.srcAcctId(), ledger.tgtAcctId(),
                ledger.bookId(), ledger.tranAmt(), ledger.feePct(), ledger.feeAmt(), ledger.capApplied(),
                Date.valueOf(ledger.ruleEffDt()));

        return new TransferResult(tx.tranId(), TransferOutcome.POSTED, "BR-11", "posted", mode, book, businessDate,
                sourceId.get(), targetId, tx.amount(), rule, fee.amount(), ledger.capApplied(),
                findAccount(sourceId.get()).orElseThrow(), findAccount(targetId).orElseThrow(), ledger);
    }

    @Override
    public EngineState state() {
        List<Account> accounts = jdbc.query("SELECT * FROM java_engine.account ORDER BY acct_id",
                JdbcTransferFeeEngine::mapAccount);
        List<LedgerRow> ledger = jdbc.query("SELECT * FROM java_engine.xfer_fee_ledger ORDER BY posted_ts, tran_id",
                (rs, i) -> new LedgerRow(rs.getString("tran_id"), rs.getDate("tran_dt").toLocalDate(),
                        rs.getLong("src_acct_id"), rs.getLong("tgt_acct_id"), rs.getString("book_id"),
                        rs.getBigDecimal("tran_amt"), rs.getBigDecimal("fee_pct"), rs.getBigDecimal("fee_amt"),
                        rs.getString("cap_applied"), rs.getDate("rule_eff_dt").toLocalDate()));
        List<FeeRule> rules = jdbc.query("SELECT * FROM java_engine.ctl_xfer_parm ORDER BY book_id, eff_dt",
                JdbcTransferFeeEngine::mapRule);
        return new EngineState(rounding.get().name(), rules, accounts, ledger);
    }

    @Override
    public RoundingMode rounding() {
        return rounding.get();
    }

    @Override
    public void setRounding(RoundingMode mode) {
        rounding.set(mode);
    }

    private Optional<Account> findAccount(long id) {
        return jdbc.query("SELECT * FROM java_engine.account WHERE acct_id = ?",
                JdbcTransferFeeEngine::mapAccount, id).stream().findFirst();
    }

    static Long targetAccountId(String desc) {
        String padded = String.format("%-100s", desc == null ? "" : desc);
        String digits = padded.substring(13, 24);
        return digits.chars().allMatch(Character::isDigit) ? Long.valueOf(digits) : null;
    }

    private static TransferRejectedException reject(DailyTransaction tx, String mode, String rule, String reason) {
        return new TransferRejectedException(
                TransferResult.notPosted(tx, TransferOutcome.REJECTED, rule, reason, mode));
    }

    private static FeeRule mapRule(ResultSet rs, int i) throws SQLException {
        return new FeeRule(rs.getString("book_id"), rs.getBigDecimal("fee_pct"), rs.getBigDecimal("fee_cap"),
                rs.getDate("eff_dt").toLocalDate(), rs.getDate("exp_dt").toLocalDate());
    }

    private static Account mapAccount(ResultSet rs, int i) throws SQLException {
        return new Account(rs.getLong("acct_id"), rs.getString("active_status"), rs.getBigDecimal("curr_bal"),
                rs.getBigDecimal("credit_limit"), rs.getBigDecimal("cash_credit_limit"), rs.getString("open_date"),
                rs.getString("expiration_date"), rs.getString("reissue_date"), rs.getBigDecimal("curr_cyc_credit"),
                rs.getBigDecimal("curr_cyc_debit"), rs.getString("addr_zip"), rs.getString("group_id"));
    }
}
