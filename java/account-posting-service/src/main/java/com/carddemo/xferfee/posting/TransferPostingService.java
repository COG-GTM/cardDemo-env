package com.carddemo.xferfee.posting;

import com.carddemo.xferfee.contracts.Account;
import com.carddemo.xferfee.contracts.FeePolicy;
import com.carddemo.xferfee.contracts.FeeResult;
import com.carddemo.xferfee.contracts.FeeRule;
import com.carddemo.xferfee.contracts.FeeSchedule;
import com.carddemo.xferfee.contracts.LedgerEntry;
import com.carddemo.xferfee.contracts.RejectReason;
import com.carddemo.xferfee.contracts.TransferPosted;
import com.carddemo.xferfee.contracts.TransferRequested;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Posts one transfer ({@code XFERFEE 2100-POST-ONE}). Must run inside a transaction owned by the
 * caller; see {@link TransferPostingRunner} for the commit modes.
 *
 * <p>No funds, status or limit checks are made (BR-13 gap, decision D4): balances may go negative.
 */
public class TransferPostingService {

    private final FeeSchedule feeSchedule;
    private final FeePolicy feePolicy;
    private final AccountRepository accounts;
    private final FeeLedgerRepository ledger;
    private final OutboxRepository outbox;

    public TransferPostingService(FeeSchedule feeSchedule, FeePolicy feePolicy,
            AccountRepository accounts, FeeLedgerRepository ledger, OutboxRepository outbox) {
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    /** A failing lookup (e.g. an ambiguous rule) aborts like XFERFEE's RULE LOOKUP FAILED. */
    private Optional<FeeRule> lookupRule(TransferRequested transfer) {
        try {
            return feeSchedule.effectiveRule(transfer.bookId(), transfer.tranDate());
        } catch (RuntimeException e) {
            throw new PostingException(transfer.tranId(), RejectReason.POSTING_ERROR,
                    "XFERFEE: RULE LOOKUP FAILED " + e.getMessage(), e);
        }
    }

    public TransferPosted post(TransferRequested transfer) {
        FeeRule rule = lookupRule(transfer)
                .orElseThrow(() -> new PostingException(transfer.tranId(), RejectReason.NO_FEE_RULE,
                        "XFERFEE: NO FEE RULE FOR BOOK " + LegacyText.pad(transfer.bookId(), 10)));
        FeeResult fee = feePolicy.apply(transfer.amount(), rule);

        Map<Long, Balances> balances = lockAccounts(transfer);
        Balances source = balances.get(transfer.sourceAccountId());
        Balances target = balances.get(transfer.targetAccountId());
        BigDecimal amount = transfer.amount();
        source.balance = source.balance.subtract(amount).subtract(fee.feeAmount());
        target.balance = target.balance.add(amount);
        target.cycleCredit = target.cycleCredit.add(amount);
        source.cycleDebit = source.cycleDebit.add(amount).add(fee.feeAmount());
        balances.forEach((id, b) -> accounts.updateBalances(id, b.balance, b.cycleCredit, b.cycleDebit));

        TransferPosted posted = new TransferPosted(transfer.tranId(), transfer.tranDate(),
                transfer.sourceAccountId(), transfer.targetAccountId(), transfer.bookId(), amount,
                rule.feePct(), fee.feeAmount(), fee.capApplied(), rule.effectiveDate());
        insertLedger(posted);
        outbox.append(posted);
        return posted;
    }

    private Map<Long, Balances> lockAccounts(TransferRequested transfer) {
        Map<Long, Account> found = accounts.lockForUpdate(
                List.of(transfer.sourceAccountId(), transfer.targetAccountId()));
        if (!found.containsKey(transfer.sourceAccountId()) || !found.containsKey(transfer.targetAccountId())) {
            throw new PostingException(transfer.tranId(), RejectReason.UNKNOWN_ACCOUNT,
                    "XFERFEE: ACCOUNT NOT FOUND " + LegacyText.digits(transfer.sourceAccountId(), 11)
                            + " / " + LegacyText.digits(transfer.targetAccountId(), 11));
        }
        Map<Long, Balances> balances = new LinkedHashMap<>();
        found.forEach((id, account) -> balances.put(id, new Balances(account)));
        return balances;
    }

    private void insertLedger(TransferPosted posted) {
        LedgerEntry entry = new LedgerEntry(posted.tranId(), posted.tranDate(), posted.sourceAccountId(),
                posted.targetAccountId(), posted.bookId(), posted.amount(), posted.feeAmount(),
                posted.capApplied());
        try {
            ledger.insert(entry);
        } catch (DuplicateKeyException e) {
            throw new PostingException(posted.tranId(), RejectReason.DUPLICATE_TRAN_ID,
                    "XFERFEE: LEDGER INSERT FAILED (DUPLICATE TRAN_ID " + posted.tranId() + ")", e);
        } catch (DataAccessException e) {
            throw new PostingException(posted.tranId(), RejectReason.POSTING_ERROR,
                    "XFERFEE: LEDGER INSERT FAILED", e);
        }
    }

    /** Mutable per-account working copy; shared when source and target are the same account. */
    private static final class Balances {
        BigDecimal balance;
        BigDecimal cycleCredit;
        BigDecimal cycleDebit;

        Balances(Account account) {
            balance = account.currentBalance();
            cycleCredit = account.currentCycleCredit();
            cycleDebit = account.currentCycleDebit();
        }
    }
}
