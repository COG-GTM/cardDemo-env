package com.carddemo.posting;

import com.carddemo.contracts.FeePolicy;
import com.carddemo.contracts.FeeResult;
import com.carddemo.contracts.FeeRule;
import com.carddemo.contracts.FeeSchedule;
import com.carddemo.contracts.TransferPosted;
import com.carddemo.contracts.TransferRequested;
import com.carddemo.posting.PostingException.Reason;
import java.sql.SQLException;
import java.util.stream.LongStream;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts one transfer (XFERFEE 2100-POST-ONE). No funds, status or credit-limit checks: the legacy
 * program has none (BR-13).
 */
@Service
public class PostingService {

    private final FeeSchedule feeSchedule;
    private final FeePolicy feePolicy;
    private final AccountRepository accounts;
    private final FeeLedgerRepository ledger;
    private final OutboxRepository outbox;

    public PostingService(FeeSchedule feeSchedule, FeePolicy feePolicy, AccountRepository accounts,
            FeeLedgerRepository ledger, OutboxRepository outbox) {
        this.feeSchedule = feeSchedule;
        this.feePolicy = feePolicy;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public TransferPosted post(TransferRequested transfer) {
        FeeRule rule = resolveRule(transfer);
        FeeResult fee = feePolicy.apply(transfer.amount(), rule);
        lockAccounts(transfer);

        accounts.debitSource(transfer.sourceAccountId(), transfer.amount(), fee.feeAmount());
        accounts.creditTarget(transfer.targetAccountId(), transfer.amount());

        TransferPosted posted = new TransferPosted(transfer.tranId(), transfer.tranDate(),
                transfer.sourceAccountId(), transfer.targetAccountId(), transfer.bookId(), transfer.amount(),
                rule.feePct(), fee.feeAmount(), fee.capApplied(), rule.effectiveDate());
        try {
            ledger.insert(posted);
        } catch (DataAccessException e) {
            throw new PostingException(Reason.LEDGER_INSERT_FAILED, transfer.tranId(),
                    "XFERFEE: LEDGER INSERT FAILED " + sqlState(e), e);
        }
        outbox.append(posted);
        return posted;
    }

    private FeeRule resolveRule(TransferRequested transfer) {
        try {
            return feeSchedule.ruleFor(transfer.bookId(), transfer.tranDate())
                    .orElseThrow(() -> new PostingException(Reason.NO_FEE_RULE, transfer.tranId(),
                            "XFERFEE: NO FEE RULE FOR BOOK " + pad(transfer.bookId(), 10), null));
        } catch (IllegalStateException | DataAccessException e) {
            throw new PostingException(Reason.RULE_LOOKUP_FAILED, transfer.tranId(),
                    "XFERFEE: RULE LOOKUP FAILED " + e.getMessage(), e);
        }
    }

    /** Both accounts must exist (BR-12); rows are locked in ascending ID order to avoid deadlocks. */
    private void lockAccounts(TransferRequested transfer) {
        boolean allFound = LongStream.of(transfer.sourceAccountId(), transfer.targetAccountId())
                .distinct()
                .sorted()
                .mapToObj(accounts::lock)
                .toList()
                .stream()
                .allMatch(java.util.Optional::isPresent);
        if (!allFound) {
            throw new PostingException(Reason.ACCOUNT_NOT_FOUND, transfer.tranId(),
                    String.format("XFERFEE: ACCOUNT NOT FOUND %011d / %011d",
                            transfer.sourceAccountId(), transfer.targetAccountId()), null);
        }
    }

    private static String sqlState(DataAccessException e) {
        return e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : "";
    }

    private static String pad(String value, int width) {
        return String.format("%-" + width + "s", value);
    }
}
